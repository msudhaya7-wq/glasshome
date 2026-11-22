package com.liquidglass.launcher

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassDialogBuilder
import com.example.liquidglass.LiquidGlassView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val io = Executors.newSingleThreadExecutor()

    private var apps: List<AppEntry> = emptyList()
    private var dockKeys: List<String> = emptyList()

    private lateinit var root: FrameLayout
    private lateinit var wallpaper: ImageView
    private lateinit var grid: RecyclerView
    private lateinit var gridAdapter: AppAdapter

    private lateinit var clockGlass: LiquidGlassView
    private lateinit var timeText: TextView
    private lateinit var dateText: TextView

    private lateinit var searchPill: LiquidGlassView
    private lateinit var dockGlass: LiquidGlassView
    private lateinit var dockRow: LinearLayout

    private lateinit var searchPanel: LiquidGlassView
    private lateinit var searchInput: EditText
    private lateinit var searchAdapter: AppAdapter
    private var searchOpen = false

    private val allGlass = mutableListOf<LiquidGlassView>()

    private val pickWallpaper =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    // Sila gallery apps persist permission tharaadhu; current session-la work aagum
                }
                prefs.wallpaperUri = uri.toString()
                loadWallpaper()
            }
        }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateClock()
    }

    // ==================== Lifecycle ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        buildUi()
        setContentView(root)
        applyInsets()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchOpen) closeSearch() else grid.smoothScrollToPosition(0)
            }
        })

        loadWallpaper()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, timeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        updateClock()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(timeReceiver)
        } catch (e: IllegalArgumentException) {
        }
    }

    override fun onResume() {
        super.onResume()
        reloadApps() // pudhu app install / uninstall aana update aagum
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button press pannaa: search close, top-ku scroll
        if (searchOpen) closeSearch() else grid.smoothScrollToPosition(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ==================== UI ====================

    private fun buildUi() {
        root = FrameLayout(this).apply { clipChildren = false }

        // 1) Wallpaper
        wallpaper = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(wallpaper, FrameLayout.LayoutParams(MATCH, MATCH))

        // 2) App grid (icons glass-ku keezha scroll aagum, adhanaala refraction theriyum)
        gridAdapter = AppAdapter(
            listMode = false,
            onClick = ::launch,
            onLongClick = ::showAppMenu
        )
        gridAdapter.showLabels = prefs.showLabels
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            adapter = gridAdapter
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        root.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))

        // 3) Clock widget
        clockGlass = makeGlass(radiusDp = 30f).apply {
            setOnClickListener { showSettings() }
        }
        val clockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(24), 0, dpi(20), 0)
        }
        val clockCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        timeText = TextView(this).apply {
            textSize = 46f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            styleWhite(this)
        }
        dateText = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            styleWhite(this)
            alpha = 0.9f
        }
        clockCol.addView(timeText)
        clockCol.addView(dateText)
        clockRow.addView(clockCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        clockRow.addView(TextView(this).apply {
            text = "•••"
            textSize = 16f
            styleWhite(this)
            alpha = 0.8f
        })
        clockGlass.addView(clockRow, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(clockGlass, FrameLayout.LayoutParams(MATCH, dpi(112)).apply {
            gravity = Gravity.TOP
            leftMargin = dpi(16); rightMargin = dpi(16)
        })

        // 4) Search pill
        searchPill = makeGlass(radiusDp = 999f).apply {
            pressScale = 1.08f
            setOnClickListener { openSearch() }
        }
        searchPill.addView(TextView(this).apply {
            text = "⌕  Search"
            textSize = 15f
            gravity = Gravity.CENTER
            styleWhite(this)
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(searchPill, FrameLayout.LayoutParams(dpi(128), dpi(40)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        })

        // 5) Dock
        dockGlass = makeGlass(radiusDp = 34f).apply { enablePressEffect = false }
        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpi(10), 0, dpi(10), 0)
        }
        dockGlass.addView(dockRow, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(dockGlass, FrameLayout.LayoutParams(MATCH, dpi(92)).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dpi(12); rightMargin = dpi(12)
        })

        // 6) Search panel (full-screen glass)
        buildSearchPanel()

        applyTintToAll()
    }

    private fun buildSearchPanel() {
        searchPanel = makeGlass(radiusDp = 36f).apply {
            visibility = View.GONE
            enablePressEffect = false
            blurAmount = 0.5f            // periya panel-ku strong blur, text padikka easy
            material = GlassMaterial.REGULAR
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(18), dpi(16), dpi(8))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchInput = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(0x99FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 17f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dpi(16), dpi(10), dpi(16), dpi(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(999f)
                setColor(0x33FFFFFF)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filterSearch(s?.toString().orEmpty())
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    searchAdapter.items.firstOrNull()?.let { launch(it) }
                    true
                } else false
            }
        }
        bar.addView(searchInput, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(TextView(this).apply {
            text = "Cancel"
            textSize = 16f
            setTextColor(0xFF64B5FF.toInt())
            setPadding(dpi(14), dpi(10), dpi(4), dpi(10))
            setOnClickListener { closeSearch() }
        })
        col.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        searchAdapter = AppAdapter(
            listMode = true,
            onClick = ::launch,
            onLongClick = ::showAppMenu
        )
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = searchAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        col.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dpi(10) })

        searchPanel.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(searchPanel, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            leftMargin = dpi(10); rightMargin = dpi(10)
        })
    }

    /** Ella glass-um ore style-la irukka oru factory. */
    private fun makeGlass(radiusDp: Float): LiquidGlassView =
        LiquidGlassView(this).apply {
            enableDynamicBackground = true   // scroll pannum podhu glass update aaganum
            useShaderPipeline = true         // API 33+ la real SDF refraction
            material = GlassMaterial.REGULAR
            cornerRadius = if (radiusDp >= 999f) 999f else dp(radiusDp)
            refractionHeight = 180f
            bevelWidth = 56f
            dispersionStrength = 0.14f
            blurAmount = 0.05f
            saturation = 150f
            enableEdgeHighlight = true
            enableSensorHighlight = true     // phone tilt panna highlight move aagum
            allGlass.add(this)
        }

    private fun styleWhite(tv: TextView) {
        tv.setTextColor(Color.WHITE)
        tv.setShadowLayer(8f, 0f, 1f, 0x66000000)
    }

    private fun applyTintToAll() {
        for (g in allGlass) {
            when (prefs.tintStyle) {
                1 -> g.setGlassTint(0xFF0A84FF.toInt(), 0.25f)
                2 -> g.setGlassTint(0xFFFF375F.toInt(), 0.22f)
                3 -> g.setGlassTint(Color.BLACK, 0.35f)
                4 -> g.setGlassTint(Color.WHITE, 0.30f)
                else -> g.glassTint = Color.TRANSPARENT
            }
        }
        // Search panel-ku konjam dark tint eppavum venum, illana text padikka kashtam
        if (prefs.tintStyle == 0 || prefs.tintStyle == 4) {
            searchPanel.setGlassTint(Color.BLACK, 0.25f)
        }
    }

    // ==================== Insets ====================

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = bars.bottom

            (clockGlass.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + dpi(12)
            (dockGlass.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom + dpi(10)
            (searchPill.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                bottom + dpi(10 + 92 + 12)
            (searchPanel.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + dpi(10)
                bottomMargin = maxOf(bottom, ime.bottom) + dpi(10)
            }

            grid.setPadding(
                dpi(8),
                bars.top + dpi(12 + 112 + 18),
                dpi(8),
                bottom + dpi(10 + 92 + 12 + 40 + 16)
            )
            root.requestLayout()
            WindowInsetsCompat.CONSUMED
        }
    }

    // ==================== Clock ====================

    private fun updateClock() {
        if (!::timeText.isInitialized) return
        val now = Date()
        val pattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        timeText.text = SimpleDateFormat(pattern, Locale.getDefault()).format(now)
        dateText.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
    }

    // ==================== Apps ====================

    private fun reloadApps() {
        io.execute {
            val loaded = AppRepository.loadApps(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                apps = loaded
                val saved = prefs.dock
                if (saved == null) prefs.dock = AppRepository.defaultDock(this, loaded)
                val installed = loaded.map { it.key }.toSet()
                dockKeys = (prefs.dock ?: emptyList()).filter { it in installed }
                refreshDock()
                refreshGrid()
                if (searchOpen) filterSearch(searchInput.text.toString())
            }
        }
    }

    private fun refreshGrid() {
        gridAdapter.items = apps.filter { it.key !in dockKeys }
    }

    private fun refreshDock() {
        dockRow.removeAllViews()
        val byKey = apps.associateBy { it.key }
        for (key in dockKeys) {
            val app = byKey[key] ?: continue
            val icon = ImageView(this).apply {
                setImageDrawable(app.icon)
                contentDescription = app.label
                setOnClickListener { launch(app) }
                setOnLongClickListener { showAppMenu(app, it); true }
            }
            val slot = FrameLayout(this)
            slot.addView(icon, FrameLayout.LayoutParams(dpi(58), dpi(58), Gravity.CENTER))
            dockRow.addView(slot, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        dockGlass.visibility = if (dockKeys.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun launch(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
            if (searchOpen) root.postDelayed({ closeSearch() }, 300)
        } catch (e: Exception) {
            Toast.makeText(this, "Open panna mudiyala", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppMenu(app: AppEntry, anchor: View) {
        val inDock = app.key in dockKeys
        val items = arrayOf(
            if (inDock) "Remove from dock" else "Add to dock",
            "App info",
            "Uninstall"
        )
        LiquidGlassDialogBuilder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleDock(app)
                    1 -> openAppInfo(app)
                    2 -> uninstall(app)
                }
            }
            .show()
    }

    private fun toggleDock(app: AppEntry) {
        val list = dockKeys.toMutableList()
        if (app.key in list) {
            list.remove(app.key)
        } else {
            if (list.size >= 4) list.removeAt(list.size - 1) // full-aa irundha last-a replace
            list.add(app.key)
        }
        dockKeys = list
        prefs.dock = list
        refreshDock()
        refreshGrid()
    }

    private fun openAppInfo(app: AppEntry) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + app.component.packageName))
            )
        } catch (e: Exception) {
        }
    }

    private fun uninstall(app: AppEntry) {
        try {
            @Suppress("DEPRECATION")
            startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.packageName))
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Indha app-a uninstall panna mudiyadhu", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== Search ====================

    private fun openSearch() {
        if (searchOpen) return
        searchOpen = true
        filterSearch("")
        searchPanel.alpha = 0f
        searchPanel.scaleX = 0.94f
        searchPanel.scaleY = 0.94f
        searchPanel.visibility = View.VISIBLE
        searchPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        searchInput.requestFocus()
        WindowCompat.getInsetsController(window, searchInput).show(WindowInsetsCompat.Type.ime())
    }

    private fun closeSearch() {
        if (!searchOpen) return
        searchOpen = false
        WindowCompat.getInsetsController(window, searchInput).hide(WindowInsetsCompat.Type.ime())
        searchPanel.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(180)
            .withEndAction {
                searchPanel.visibility = View.GONE
                searchInput.setText("")
            }.start()
    }

    private fun filterSearch(q: String) {
        val query = q.trim()
        searchAdapter.items =
            if (query.isEmpty()) apps
            else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    // ==================== Settings ====================

    private fun showSettings() {
        val items = arrayOf(
            "Change wallpaper",
            "Default wallpaper",
            "Glass colour",
            if (prefs.showLabels) "Hide app names" else "Show app names",
            "Set as default Home app"
        )
        LiquidGlassDialogBuilder(this)
            .setTitle("Glass Home")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickWallpaper.launch(arrayOf("image/*"))
                    1 -> { prefs.wallpaperUri = null; loadWallpaper() }
                    2 -> showTintPicker()
                    3 -> {
                        prefs.showLabels = !prefs.showLabels
                        gridAdapter.showLabels = prefs.showLabels
                    }
                    4 -> openHomeSettings()
                }
            }
            .show()
    }

    private fun showTintPicker() {
        val names = arrayOf("Clear (iOS)", "Blue", "Pink", "Dark", "Frosted white")
        LiquidGlassDialogBuilder(this)
            .setTitle("Glass colour")
            .setSingleChoiceItems(names, prefs.tintStyle) { dialog, which ->
                prefs.tintStyle = which
                applyTintToAll()
                dialog.dismiss()
            }
            .show()
    }

    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ==================== Wallpaper ====================

    private fun loadWallpaper() {
        val uriStr = prefs.wallpaperUri
        if (uriStr == null) {
            wallpaper.setImageResource(R.drawable.default_wallpaper)
            return
        }
        val dm = resources.displayMetrics
        val targetW = dm.widthPixels
        val targetH = dm.heightPixels
        io.execute {
            val bmp = decodeSampled(Uri.parse(uriStr), targetW, targetH)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bmp != null) {
                    wallpaper.setImageBitmap(bmp)
                } else {
                    prefs.wallpaperUri = null
                    wallpaper.setImageResource(R.drawable.default_wallpaper)
                    Toast.makeText(this, "Wallpaper load aagala", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun decodeSampled(uri: Uri, reqW: Int, reqH: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }

    // ==================== Adapter ====================

    /** Grid (home screen) mattum list (search) rendukkum ore adapter. */
    private inner class AppAdapter(
        private val listMode: Boolean,
        private val onClick: (AppEntry) -> Unit,
        private val onLongClick: (AppEntry, View) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.VH>() {

        var items: List<AppEntry> = emptyList()
            @SuppressLint("NotifyDataSetChanged")
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        var showLabels: Boolean = true
            @SuppressLint("NotifyDataSetChanged")
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        inner class VH(view: View, val icon: ImageView, val label: TextView) :
            RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val icon = ImageView(ctx)
            val label = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            val container = LinearLayout(ctx)
            if (listMode) {
                container.orientation = LinearLayout.HORIZONTAL
                container.gravity = Gravity.CENTER_VERTICAL
                container.setPadding(dpi(6), dpi(8), dpi(6), dpi(8))
                label.textSize = 16f
                container.addView(icon, LinearLayout.LayoutParams(dpi(44), dpi(44)))
                container.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    leftMargin = dpi(14)
                })
                container.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            } else {
                container.orientation = LinearLayout.VERTICAL
                container.gravity = Gravity.CENTER_HORIZONTAL
                container.setPadding(dpi(4), dpi(10), dpi(4), dpi(10))
                label.textSize = 12f
                label.gravity = Gravity.CENTER
                label.setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
                container.addView(icon, LinearLayout.LayoutParams(dpi(58), dpi(58)))
                container.addView(label, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dpi(6)
                })
                container.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            }
            return VH(container, icon, label)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = items[position]
            holder.icon.setImageDrawable(app.icon)
            holder.label.text = app.label
            holder.label.visibility =
                if (listMode || showLabels) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { v ->
                v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }.start()
                onClick(app)
            }
            holder.itemView.setOnLongClickListener { v -> onLongClick(app, v); true }
        }

        override fun getItemCount(): Int = items.size
    }
}
