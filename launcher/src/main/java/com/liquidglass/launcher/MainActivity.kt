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
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassDialogBuilder
import com.example.liquidglass.LiquidGlassView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val COLS = 4

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/**
 * iPhone (iOS 26) style home screen:
 *  - pakkam pakkamaa swipe panra app pages (4 columns)
 *  - first page-la Liquid Glass date/clock widget
 *  - keela "Search" pill (swipe pannum podhu page dots)
 *  - glass dock, labels illaadha 4 apps
 *  - keezha swipe down panna Spotlight search
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val io = Executors.newSingleThreadExecutor()

    private var apps: List<AppEntry> = emptyList()
    private var dockKeys: List<String> = emptyList()

    private lateinit var root: FrameLayout
    private lateinit var wallpaper: ImageView

    // Pages
    private lateinit var pager: RecyclerView
    private val pagesAdapter = PagesAdapter()
    private var pages: List<List<AppEntry>> = emptyList()
    private var currentPage = 0
    private var topPad = 0
    private var bottomPad = 0
    private var rowsPerPage = 6

    // Widget
    private lateinit var widgetGlass: LiquidGlassView
    private lateinit var weekdayText: TextView
    private lateinit var dayNumText: TextView
    private lateinit var monthText: TextView
    private lateinit var timeText: TextView
    private lateinit var ampmText: TextView

    // Search pill + dots
    private lateinit var pill: LiquidGlassView
    private lateinit var pillLabel: TextView
    private lateinit var dotsRow: LinearLayout
    private val showLabelRunnable = Runnable { showPillLabel(true) }

    // Dock
    private lateinit var dockGlass: LiquidGlassView
    private lateinit var dockRow: LinearLayout

    // Spotlight search
    private lateinit var searchPanel: LiquidGlassView
    private lateinit var searchInput: EditText
    private lateinit var searchAdapter: SearchAdapter
    private var searchOpen = false

    private val allGlass = mutableListOf<LiquidGlassView>()

    // Swipe-down detection
    private var downX = 0f
    private var downY = 0f

    private val pickWallpaper =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
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
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        buildUi()
        setContentView(root)
        applyInsets()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchOpen) closeSearch() else goToFirstPage()
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
        reloadApps()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (searchOpen) closeSearch() else goToFirstPage()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    /** Home screen-la keezha swipe panna Spotlight search open aagum (iPhone maadhiri). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!searchOpen) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (dy > dp(90f) && abs(dy) > 2 * abs(dx)) {
                        openSearch()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ==================== UI ====================

    private fun buildUi() {
        root = FrameLayout(this).apply { clipChildren = false }

        wallpaper = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(wallpaper, FrameLayout.LayoutParams(MATCH, MATCH))

        buildWidget()

        pager = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = pagesAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val w = rv.width
                    if (w > 0) {
                        val p = (rv.computeHorizontalScrollOffset() + w / 2) / w
                        if (p != currentPage) {
                            currentPage = p
                            updateDots()
                        }
                    }
                }

                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    root.removeCallbacks(showLabelRunnable)
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        root.postDelayed(showLabelRunnable, 1200)
                    } else {
                        showPillLabel(false)
                    }
                }
            })
        }
        PagerSnapHelper().attachToRecyclerView(pager)
        root.addView(pager, FrameLayout.LayoutParams(MATCH, MATCH))

        // Search pill (iOS 26: idle-la "Search", swipe pannum podhu page dots)
        pill = makeGlass(radiusDp = 999f).apply {
            pressScale = 1.08f
            setOnClickListener { openSearch() }
        }
        val pillContent = FrameLayout(this)
        pillLabel = TextView(this).apply {
            text = "⌕ Search"
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            styleWhite(this)
        }
        dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            alpha = 0f
        }
        pillContent.addView(pillLabel, FrameLayout.LayoutParams(MATCH, MATCH))
        pillContent.addView(dotsRow, FrameLayout.LayoutParams(MATCH, MATCH))
        pill.addView(pillContent, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(pill, FrameLayout.LayoutParams(dpi(112), dpi(34)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        })

        // Dock
        dockGlass = makeGlass(radiusDp = 36f).apply { enablePressEffect = false }
        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpi(8), 0, dpi(8), 0)
        }
        dockGlass.addView(dockRow, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(dockGlass, FrameLayout.LayoutParams(MATCH, dpi(92)).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dpi(10); rightMargin = dpi(10)
        })

        buildSearchPanel()
        applyTintToAll()
    }

    /** iOS "Calendar + Clock" maadhiri wide glass widget. */
    private fun buildWidget() {
        widgetGlass = makeGlass(radiusDp = 26f).apply {
            backdropSource = wallpaper   // RecyclerView-kulla irukkuradhaala wallpaper-a direct-aa sample pannum
            setOnClickListener { showSettings() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(20), dpi(16), dpi(20), dpi(16))
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        weekdayText = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFFFF453A.toInt())   // iOS calendar red
            letterSpacing = 0.04f
        }
        dayNumText = TextView(this).apply {
            textSize = 52f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            styleWhite(this)
            includeFontPadding = false
        }
        monthText = TextView(this).apply {
            textSize = 13f
            styleWhite(this)
            alpha = 0.75f
        }
        left.addView(weekdayText)
        left.addView(dayNumText)
        left.addView(monthText)

        val divider = View(this).apply { setBackgroundColor(0x33FFFFFF) }

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        timeText = TextView(this).apply {
            textSize = 50f
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            styleWhite(this)
            includeFontPadding = false
        }
        ampmText = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            styleWhite(this)
            setPadding(dpi(4), 0, 0, dpi(8))
        }
        timeRow.addView(timeText)
        timeRow.addView(ampmText)
        right.addView(timeRow)

        row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(divider, LinearLayout.LayoutParams(dpi(1), MATCH).apply {
            topMargin = dpi(8); bottomMargin = dpi(8)
        })
        row.addView(right, LinearLayout.LayoutParams(0, MATCH, 1.3f))
        widgetGlass.addView(row, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun buildSearchPanel() {
        searchPanel = makeGlass(radiusDp = 38f).apply {
            visibility = View.GONE
            enablePressEffect = false
            blurAmount = 0.5f
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
            hint = "⌕  Search"
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

        col.addView(TextView(this).apply {
            text = "APPS"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xB3FFFFFF.toInt())
            letterSpacing = 0.06f
            setPadding(dpi(6), dpi(16), 0, dpi(4))
        })

        searchAdapter = SearchAdapter()
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = searchAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        col.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))

        searchPanel.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(searchPanel, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            leftMargin = dpi(10); rightMargin = dpi(10)
        })
    }

    private fun makeGlass(radiusDp: Float): LiquidGlassView =
        LiquidGlassView(this).apply {
            enableDynamicBackground = true
            useShaderPipeline = true
            material = GlassMaterial.REGULAR
            cornerRadius = if (radiusDp >= 999f) 999f else dp(radiusDp)
            refractionHeight = 180f
            bevelWidth = 56f
            dispersionStrength = 0.14f
            blurAmount = 0.05f
            saturation = 150f
            enableEdgeHighlight = true
            enableSensorHighlight = true
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
        if (prefs.tintStyle == 0 || prefs.tintStyle == 4) {
            searchPanel.setGlassTint(Color.BLACK, 0.25f)
        }
    }

    // ==================== Insets & layout ====================

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = bars.bottom

            (dockGlass.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom + dpi(10)
            (pill.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom + dpi(10 + 92 + 14)
            (searchPanel.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + dpi(10)
                bottomMargin = maxOf(bottom, ime.bottom) + dpi(10)
            }

            val newTop = bars.top + dpi(14)
            val newBottom = bottom + dpi(10 + 92 + 14 + 34 + 10)
            if (newTop != topPad || newBottom != bottomPad) {
                topPad = newTop
                bottomPad = newBottom
                root.post { rebuildPages() }
            }
            root.requestLayout()
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun cellHeight(): Int = dpi(100)

    /** Screen height-ku etha maadhiri apps-a pages-aa pirikkum. */
    private fun rebuildPages() {
        val h = root.height
        if (h == 0) {
            root.post { rebuildPages() }
            return
        }
        rowsPerPage = maxOf(3, (h - topPad - bottomPad) / cellHeight())
        val perPage = rowsPerPage * COLS
        val firstPageCap = maxOf(0, (rowsPerPage - 2) * COLS) // widget 2 rows edukkum

        val gridApps = apps.filter { it.key !in dockKeys }
        val result = mutableListOf<List<AppEntry>>()
        result.add(gridApps.take(firstPageCap))
        var i = firstPageCap
        while (i < gridApps.size) {
            result.add(gridApps.subList(i, minOf(i + perPage, gridApps.size)))
            i += perPage
        }
        pages = result
        if (currentPage >= pages.size) currentPage = pages.size - 1
        pagesAdapter.refresh()
        buildDots()
    }

    private fun goToFirstPage() {
        if (::pager.isInitialized) pager.smoothScrollToPosition(0)
    }

    // ==================== Dots ====================

    private fun buildDots() {
        dotsRow.removeAllViews()
        for (i in pages.indices) {
            dotsRow.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            }, LinearLayout.LayoutParams(dpi(7), dpi(7)).apply {
                leftMargin = dpi(4); rightMargin = dpi(4)
            })
        }
        updateDots()
    }

    private fun updateDots() {
        for (i in 0 until dotsRow.childCount) {
            dotsRow.getChildAt(i).alpha = if (i == currentPage) 1f else 0.35f
        }
    }

    private fun showPillLabel(show: Boolean) {
        pillLabel.animate().alpha(if (show) 1f else 0f).setDuration(180).start()
        dotsRow.animate().alpha(if (show) 0f else 1f).setDuration(180).start()
    }

    // ==================== Clock ====================

    private fun updateClock() {
        if (!::timeText.isInitialized) return
        val now = Date()
        val loc = Locale.getDefault()
        weekdayText.text = SimpleDateFormat("EEEE", loc).format(now).uppercase(loc)
        dayNumText.text = SimpleDateFormat("d", loc).format(now)
        monthText.text = SimpleDateFormat("MMMM yyyy", loc).format(now)
        if (DateFormat.is24HourFormat(this)) {
            timeText.text = SimpleDateFormat("HH:mm", loc).format(now)
            ampmText.text = ""
        } else {
            timeText.text = SimpleDateFormat("h:mm", loc).format(now)
            ampmText.text = SimpleDateFormat("a", loc).format(now).uppercase(loc)
        }
    }

    // ==================== Apps ====================

    private fun reloadApps() {
        io.execute {
            val loaded = AppRepository.loadApps(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                apps = loaded
                if (prefs.dock == null) prefs.dock = AppRepository.defaultDock(this, loaded)
                val installed = loaded.map { it.key }.toSet()
                dockKeys = (prefs.dock ?: emptyList()).filter { it in installed }
                refreshDock()
                rebuildPages()
                if (searchOpen) filterSearch(searchInput.text.toString())
            }
        }
    }

    private fun refreshDock() {
        dockRow.removeAllViews()
        val byKey = apps.associateBy { it.key }
        for (key in dockKeys) {
            val app = byKey[key] ?: continue
            val icon = ImageView(this).apply {
                setImageDrawable(app.freshIcon())
                contentDescription = app.label
                setOnClickListener { v -> bounce(v); launch(app) }
                setOnLongClickListener { v -> showAppMenu(app, v); true }
            }
            val slot = FrameLayout(this)
            slot.addView(icon, FrameLayout.LayoutParams(dpi(62), dpi(62), Gravity.CENTER))
            dockRow.addView(slot, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        dockGlass.visibility = if (dockKeys.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    /** Oru app icon + peru (iOS style). */
    private fun makeAppCell(app: AppEntry): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dpi(8), 0, 0)
        }
        val icon = ImageView(this).apply {
            setImageDrawable(app.freshIcon())
            contentDescription = app.label
        }
        cell.addView(icon, LinearLayout.LayoutParams(dpi(62), dpi(62)))
        if (prefs.showLabels) {
            cell.addView(TextView(this).apply {
                text = app.label
                textSize = 11.5f
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.WHITE)
                setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
                setPadding(dpi(2), 0, dpi(2), 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(5) })
        }
        cell.setOnClickListener { v -> bounce(icon); launch(app) }
        cell.setOnLongClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showAppMenu(app, v); true
        }
        return cell
    }

    private fun bounce(v: View) {
        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }.start()
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
            if (inDock) "Remove from Dock" else "Add to Dock",
            "App Info",
            "Remove App"
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
            if (list.size >= 4) list.removeAt(list.size - 1)
            list.add(app.key)
        }
        dockKeys = list
        prefs.dock = list
        refreshDock()
        rebuildPages()
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
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.packageName)))
        } catch (e: Exception) {
            Toast.makeText(this, "Indha app-a remove panna mudiyadhu", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== Search ====================

    private fun openSearch() {
        if (searchOpen) return
        searchOpen = true
        filterSearch("")
        searchPanel.alpha = 0f
        searchPanel.translationY = -dp(40f)
        searchPanel.visibility = View.VISIBLE
        searchPanel.animate().alpha(1f).translationY(0f).setDuration(240).start()
        searchInput.requestFocus()
        WindowCompat.getInsetsController(window, searchInput).show(WindowInsetsCompat.Type.ime())
    }

    private fun closeSearch() {
        if (!searchOpen) return
        searchOpen = false
        WindowCompat.getInsetsController(window, searchInput).hide(WindowInsetsCompat.Type.ime())
        searchPanel.animate().alpha(0f).translationY(-dp(40f)).setDuration(180)
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
            "Change Wallpaper",
            "Default Wallpaper",
            "Glass Colour",
            if (prefs.showLabels) "Hide App Names" else "Show App Names",
            "Set as Default Home App"
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
                        pagesAdapter.refresh()
                    }
                    4 -> openHomeSettings()
                }
            }
            .show()
    }

    private fun showTintPicker() {
        val names = arrayOf("Clear (iOS)", "Blue", "Pink", "Dark", "Frosted White")
        LiquidGlassDialogBuilder(this)
            .setTitle("Glass Colour")
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

    // ==================== Adapters ====================

    /** Ovvoru item-um oru full home screen page. */
    private inner class PagesAdapter : RecyclerView.Adapter<PagesAdapter.PageVH>() {

        inner class PageVH(val page: LinearLayout) : RecyclerView.ViewHolder(page)

        @SuppressLint("NotifyDataSetChanged")
        fun refresh() = notifyDataSetChanged()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val page = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(MATCH, MATCH)
            }
            return PageVH(page)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            val page = holder.page
            page.removeAllViews()
            page.setPadding(dpi(14), topPad, dpi(14), bottomPad)

            val list = pages[position]
            var rows = rowsPerPage

            if (position == 0) {
                (widgetGlass.parent as? ViewGroup)?.removeView(widgetGlass)
                page.addView(widgetGlass, LinearLayout.LayoutParams(MATCH, cellHeight() * 2 - dpi(16)).apply {
                    leftMargin = dpi(6); rightMargin = dpi(6)
                    topMargin = dpi(4); bottomMargin = dpi(12)
                })
                rows -= 2
                updateClock()
            }

            for (r in 0 until rows) {
                val rowView = LinearLayout(page.context).apply { orientation = LinearLayout.HORIZONTAL }
                for (c in 0 until COLS) {
                    val idx = r * COLS + c
                    val lp = LinearLayout.LayoutParams(0, MATCH, 1f)
                    if (idx < list.size) rowView.addView(makeAppCell(list[idx]), lp)
                    else rowView.addView(Space(page.context), lp)
                }
                page.addView(rowView, LinearLayout.LayoutParams(MATCH, cellHeight()))
            }
        }

        override fun getItemCount(): Int = pages.size
    }

    /** Spotlight search results list. */
    private inner class SearchAdapter : RecyclerView.Adapter<SearchAdapter.VH>() {

        var items: List<AppEntry> = emptyList()
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
                textSize = 16f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(6), dpi(8), dpi(6), dpi(8))
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            }
            row.addView(icon, LinearLayout.LayoutParams(dpi(44), dpi(44)))
            row.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dpi(14) })
            return VH(row, icon, label)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = items[position]
            holder.icon.setImageDrawable(app.freshIcon())
            holder.label.text = app.label
            holder.itemView.setOnClickListener { launch(app) }
            holder.itemView.setOnLongClickListener { v -> showAppMenu(app, v); true }
        }

        override fun getItemCount(): Int = items.size
    }
}
