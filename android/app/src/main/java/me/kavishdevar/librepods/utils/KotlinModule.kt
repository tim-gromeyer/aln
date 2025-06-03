package me.kavishdevar.librepods.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.ParcelUuid
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.XposedHooker

private const val TAG = "AirPodsHook"
private lateinit var module: KotlinModule

class KotlinModule(base: XposedInterface, param: ModuleLoadedParam): XposedModule(base, param) {
    init {
        Log.i(TAG, "AirPodsHook module initialized at :: ${param.processName}")
        module = this
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        Log.i(TAG, "onPackageLoaded :: ${param.packageName}")

        if (param.packageName == "com.google.android.bluetooth" || param.packageName == "com.android.bluetooth") {
            Log.i(TAG, "Bluetooth app detected, hooking l2c_fcr_chk_chan_modes")

            try {
                if (param.isFirstPackage) {
                    Log.i(TAG, "Loading native library for Bluetooth hook")
                    System.loadLibrary("l2c_fcr_hook")
                    Log.i(TAG, "Native library loaded successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library: ${e.message}", e)
            }
        }

        if (param.packageName == "com.android.settings") {
            Log.i(TAG, "Settings app detected, hooking Bluetooth icon handling")
            try {
                val headerControllerClass = param.classLoader.loadClass(
                    "com.android.settings.bluetooth.AdvancedBluetoothDetailsHeaderController")

                val updateIconMethod = headerControllerClass.getDeclaredMethod(
                    "updateIcon",
                    android.widget.ImageView::class.java,
                    String::class.java)

                hook(updateIconMethod, BluetoothIconHooker::class.java)
                Log.i(TAG, "Successfully hooked updateIcon method in Bluetooth settings")

                try {
                    val displayPreferenceMethod = headerControllerClass.getDeclaredMethod(
                        "displayPreference",
                        param.classLoader.loadClass("androidx.preference.PreferenceScreen"))

                    hook(displayPreferenceMethod, BluetoothSettingsAirPodsHooker::class.java)
                    Log.i(TAG, "Successfully hooked displayPreference for AirPods button injection")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to hook displayPreference: ${e.message}", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hook Bluetooth icon handler: ${e.message}", e)
            }
        }

        if (param.packageName == "com.android.systemui") {
            Log.i(TAG, "SystemUI detected, hooking volume panel")
            try {
                val volumePanelViewClass = param.classLoader.loadClass("com.android.systemui.volume.VolumeDialogImpl")

                try {
                    val initDialogMethod = volumePanelViewClass.getDeclaredMethod("initDialog", Int::class.java)
                    hook(initDialogMethod, VolumeDialogInitHooker::class.java)
                    Log.i(TAG, "Hooked initDialog method successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to hook initDialog method: ${e.message}")
                }

                try {
                    val showHMethod = volumePanelViewClass.getDeclaredMethod("showH", Int::class.java, Boolean::class.java, Int::class.java)
                    hook(showHMethod, VolumeDialogShowHooker::class.java)
                    Log.i(TAG, "Hooked showH method successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to hook showH method: ${e.message}")
                }

                Log.i(TAG, "Volume panel hook setup attempted on multiple methods")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hook volume panel: ${e.message}", e)
            }
        }
    }

    @XposedHooker
    class VolumeDialogInitHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @AfterInvocation
            fun afterInitDialog(callback: AfterHookCallback) {
                try {
                    val volumeDialog = callback.thisObject
                    Log.i(TAG, "Volume dialog initialized, adding AirPods controls")
                    addAirPodsControlsToDialog(volumeDialog!!)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in initDialog hook: ${e.message}", e)
                }
            }
        }
    }

    @XposedHooker
    class VolumeDialogShowHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @AfterInvocation
            fun afterShowH(callback: AfterHookCallback) {
                try {
                    val volumeDialog = callback.thisObject
                    Log.i(TAG, "Volume dialog shown, ensuring AirPods controls are added")
                    addAirPodsControlsToDialog(volumeDialog!!)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in showH hook: ${e.message}", e)
                }
            }
        }
    }

    @XposedHooker
    class BluetoothSettingsAirPodsHooker : XposedInterface.Hooker {
        companion object {
            private const val AIRPODS_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a"
            private const val LIBREPODS_PREFERENCE_KEY = "librepods_open_preference"
            private const val ACTION_SET_ANC_MODE = "me.kavishdevar.librepods.SET_ANC_MODE"
            private const val EXTRA_ANC_MODE = "anc_mode"

            private const val ANC_MODE_OFF = 1
            private const val ANC_MODE_NOISE_CANCELLATION = 2
            private const val ANC_MODE_TRANSPARENCY = 3
            private const val ANC_MODE_ADAPTIVE = 4

            private var currentAncMode = ANC_MODE_NOISE_CANCELLATION

            @JvmStatic
            @AfterInvocation
            fun afterDisplayPreference(callback: AfterHookCallback) {
                try {
                    val controller = callback.thisObject!!
                    val preferenceScreen = callback.args[0]!!

                    val context = preferenceScreen.javaClass.getMethod("getContext").invoke(preferenceScreen) as Context

                    val deviceField = controller.javaClass.getDeclaredField("mCachedDevice")
                    deviceField.isAccessible = true
                    val cachedDevice = deviceField.get(controller) ?: return

                    val getDeviceMethod = cachedDevice.javaClass.getMethod("getDevice")
                    val bluetoothDevice = getDeviceMethod.invoke(cachedDevice) ?: return

                    val uuidsMethod = bluetoothDevice.javaClass.getMethod("getUuids")
                    val uuids = uuidsMethod.invoke(bluetoothDevice) as? Array<ParcelUuid>

                    if (uuids != null) {
                        val isAirPods = uuids.any { it.uuid.toString() == AIRPODS_UUID }

                        if (isAirPods) {
                            Log.i(TAG, "AirPods device detected in settings, injecting controls")

                            val findPreferenceMethod = preferenceScreen.javaClass.getMethod("findPreference", CharSequence::class.java)
                            val existingPref = findPreferenceMethod.invoke(preferenceScreen, LIBREPODS_PREFERENCE_KEY)

                            if (existingPref != null) {
                                Log.i(TAG, "LIBREPODS button already exists, skipping")
                                return
                            }

                            val preferenceClass = preferenceScreen.javaClass.classLoader.loadClass("androidx.preference.Preference")
                            val preference = preferenceClass.getConstructor(Context::class.java).newInstance(context)

                            val setKeyMethod = preferenceClass.getMethod("setKey", String::class.java)
                            setKeyMethod.invoke(preference, LIBREPODS_PREFERENCE_KEY)

                            val setTitleMethod = preferenceClass.getMethod("setTitle", CharSequence::class.java)
                            setTitleMethod.invoke(preference, "Open LibrePods")

                            val setSummaryMethod = preferenceClass.getMethod("setSummary", CharSequence::class.java)
                            setSummaryMethod.invoke(preference, "Control AirPods features")

                            val setIconMethod = preferenceClass.getMethod("setIcon", Int::class.java)
                            setIconMethod.invoke(preference, android.R.drawable.ic_menu_manage)

                            val setOrderMethod = preferenceClass.getMethod("setOrder", Int::class.java)
                            setOrderMethod.invoke(preference, 1000)

                            val intent = Intent().apply {
                                setClassName("me.kavishdevar.librepods", "me.kavishdevar.librepods.MainActivity")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            val setIntentMethod = preferenceClass.getMethod("setIntent", Intent::class.java)
                            setIntentMethod.invoke(preference, intent)

                            val addPreferenceMethod = preferenceScreen.javaClass.getMethod("addPreference", preferenceClass)
                            addPreferenceMethod.invoke(preferenceScreen, preference)

                            Log.i(TAG, "Successfully added Open LIBREPODS button to AirPods settings")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in BluetoothSettingsAirPodsHooker: ${e.message}", e)
                    e.printStackTrace()
                }
            }
        }
    }

    @XposedHooker
    class BluetoothIconHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @AfterInvocation
            fun afterUpdateIcon(callback: AfterHookCallback) {
                Log.i(TAG, "BluetoothIconHooker called with args: ${callback.args.joinToString(", ")}")
                try {
                    val imageView = callback.args[0] as ImageView
                    val iconUri = callback.args[1] as String

                    val uri = android.net.Uri.parse(iconUri)
                    if (uri.toString().startsWith("android.resource://me.kavishdevar.librepods")) {
                        Log.i(TAG, "Handling AirPods icon URI: $uri")

                        try {
                            val context = imageView.context

                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                try {
                                    val packageName = uri.authority
                                    val packageContext = context.createPackageContext(
                                        packageName,
                                        Context.CONTEXT_IGNORE_SECURITY
                                    )

                                    val resPath = uri.pathSegments
                                    if (resPath.size >= 2 && resPath[0] == "drawable") {
                                        val resourceName = resPath[1]
                                        val resourceId = packageContext.resources.getIdentifier(
                                            resourceName, "drawable", packageName
                                        )

                                        if (resourceId != 0) {
                                            val drawable = packageContext.resources.getDrawable(
                                                resourceId, packageContext.theme
                                            )

                                            imageView.setImageDrawable(drawable)
                                            imageView.alpha = 1.0f

                                            callback.result = null

                                            Log.i(TAG, "Successfully loaded icon from resource: $resourceName")
                                        } else {
                                            Log.e(TAG, "Resource not found: $resourceName")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error loading resource from URI $uri: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error accessing context: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in BluetoothIconHooker: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    override fun getApplicationInfo(): ApplicationInfo {
        return super.applicationInfo
    }

    companion object {
        private const val ANC_MODE_OFF = 1
        private const val ANC_MODE_NOISE_CANCELLATION = 2
        private const val ANC_MODE_TRANSPARENCY = 3
        private const val ANC_MODE_ADAPTIVE = 4

        private var currentANCMode = ANC_MODE_NOISE_CANCELLATION

        private const val ACTION_SET_ANC_MODE = "me.kavishdevar.librepods.SET_ANC_MODE"
        private const val EXTRA_ANC_MODE = "anc_mode"
        private const val ANIMATION_DURATION = 250L

        private fun addAirPodsControlsToDialog(volumeDialog: Any) {
            try {
                val contextField = volumeDialog.javaClass.getDeclaredField("mContext")
                contextField.isAccessible = true
                val context = contextField.get(volumeDialog) as Context

                val dialogViewField = volumeDialog.javaClass.getDeclaredField("mDialogView")
                dialogViewField.isAccessible = true
                val dialogView = dialogViewField.get(volumeDialog) as ViewGroup

                val dialogRowsViewField = volumeDialog.javaClass.getDeclaredField("mDialogRowsView")
                dialogRowsViewField.isAccessible = true
                val dialogRowsView = dialogRowsViewField.get(volumeDialog) as ViewGroup

                Log.d(TAG, "Found dialogRowsView: ${dialogRowsView.javaClass.name}")

                val existingContainer = dialogView.findViewWithTag<View>("airpods_container")
                if (existingContainer != null) {
                    Log.d(TAG, "AirPods container already exists, ensuring visibility state")
                    val drawer = existingContainer.findViewWithTag<View>("airpods_drawer_container")
                    drawer?.visibility = View.GONE
                    drawer?.alpha = 0f
                    drawer?.translationY = 0f
                    val button = existingContainer.findViewWithTag<ImageButton>("airpods_button")
                    button?.visibility = View.VISIBLE
                    button?.alpha = 1f
                    if (button != null) {
                         updateMainButtonIcon(context, button, currentANCMode)
                    }
                    return
                }

                val newAirPodsButton = ImageButton(context).apply {
                    tag = "airpods_button"

                    try {
                        val airPodsPackage = context.createPackageContext(
                            "me.kavishdevar.librepods",
                            Context.CONTEXT_IGNORE_SECURITY
                        )
                        val airPodsIconRes = airPodsPackage.resources.getIdentifier(
                            "airpods", "drawable", "me.kavishdevar.librepods")

                        if (airPodsIconRes != 0) {
                            val airPodsDrawable = airPodsPackage.resources.getDrawable(
                                airPodsIconRes, airPodsPackage.theme)
                            setImageDrawable(airPodsDrawable)
                        } else {
                            setImageResource(android.R.drawable.ic_media_play)
                            Log.d(TAG, "Using fallback icon because airpods icon resource not found")
                        }
                    } catch (e: Exception) {
                        setImageResource(android.R.drawable.ic_media_play)
                        Log.e(TAG, "Failed to load AirPods icon: ${e.message}")
                    }

                    val shape = GradientDrawable()
                    shape.shape = GradientDrawable.RECTANGLE
                    shape.setColor(Color.BLACK)
                    background = shape

                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE

                    setPadding(24, 24, 24, 24)

                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        90
                    )
                    params.gravity = Gravity.CENTER
                    params.setMargins(0, 0, 0, 0)
                    layoutParams = params

                    setOnClickListener {
                        Log.d(TAG, "AirPods button clicked, toggling drawer")
                        val container = findAirPodsContainer(this)
                        val drawerContainer = container?.findViewWithTag<View>("airpods_drawer_container")
                        if (drawerContainer != null && container != null) {
                            if (drawerContainer.visibility == View.VISIBLE) {
                                hideAirPodsDrawer(container, this, drawerContainer)
                            } else {
                                showAirPodsDrawer(container, this, drawerContainer)
                            }
                        } else {
                             Log.e(TAG, "Could not find container or drawer for toggle")
                        }
                    }

                    contentDescription = "AirPods Settings"
                }

                val airPodsContainer = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    tag = "airpods_container"
                }

                newAirPodsButton.setOnLongClickListener {
                    Log.d(TAG, "AirPods button long-pressed, opening QuickSettingsDialogActivity")
                    val intent = Intent().apply {
                        setClassName("me.kavishdevar.librepods", "me.kavishdevar.librepods.QuickSettingsDialogActivity")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    try {
                        val dismissMethod = volumeDialog.javaClass.getMethod("dismissH")
                        dismissMethod.invoke(volumeDialog)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not dismiss volume dialog: ${e.message}")
                    }
                    true
                }

                val airPodsDrawer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.TOP
                    }
                    tag = "airpods_drawer_container"
                    visibility = View.GONE
                    alpha = 0f

                    val drawerShape = GradientDrawable()
                    drawerShape.shape = GradientDrawable.RECTANGLE
                    drawerShape.setColor(Color.BLACK)
                    background = drawerShape

                    setPadding(16, 8, 16, 8)
                }

                val buttonContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.TOP
                    }
                    tag = "airpods_button_container"
                }

                val modes = listOf(ANC_MODE_OFF, ANC_MODE_TRANSPARENCY, ANC_MODE_ADAPTIVE, ANC_MODE_NOISE_CANCELLATION)
                for (mode in modes) {
                    val modeOption = createAncModeOption(context, mode, mode == currentANCMode, newAirPodsButton)
                    airPodsDrawer.addView(modeOption)
                }

                buttonContainer.addView(newAirPodsButton)

                airPodsContainer.addView(airPodsDrawer)
                airPodsContainer.addView(buttonContainer)

                val settingsViewField = try {
                    val field = volumeDialog.javaClass.getDeclaredField("mSettingsView")
                    field.isAccessible = true
                    field.get(volumeDialog) as? View
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get settings view field: ${e.message}")
                    null
                }

                if (settingsViewField != null && settingsViewField.parent is ViewGroup) {
                    val settingsParent = settingsViewField.parent as ViewGroup
                    val settingsIndex = findViewIndexInParent(settingsParent, settingsViewField)

                    if (settingsIndex >= 0) {
                        settingsParent.addView(airPodsContainer, settingsIndex)
                        Log.i(TAG, "Added AirPods controls before settings button")
                    } else {
                        settingsParent.addView(airPodsContainer)
                        Log.i(TAG, "Added AirPods controls to the end of settings parent")
                    }
                } else {
                    dialogView.addView(airPodsContainer)
                    Log.i(TAG, "Fallback: Added AirPods controls to dialog view")
                }

                updateMainButtonIcon(context, newAirPodsButton, currentANCMode)

                Log.i(TAG, "Successfully added AirPods button and drawer to volume dialog")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding AirPods button to volume panel: ${e.message}")
                e.printStackTrace()
            }
        }

        private fun findViewIndexInParent(parent: ViewGroup, view: View): Int {
            for (i in 0 until parent.childCount) {
                if (parent.getChildAt(i) == view) {
                    return i
                }
            }
            return -1
        }

        private fun updateMainButtonIcon(context: Context, button: ImageButton, mode: Int) {
            try {
                val pkgContext = context.createPackageContext(
                    "me.kavishdevar.librepods",
                    Context.CONTEXT_IGNORE_SECURITY
                )

                val resName = when (mode) {
                    ANC_MODE_OFF -> "noise_cancellation"
                    ANC_MODE_TRANSPARENCY -> "transparency"
                    ANC_MODE_ADAPTIVE -> "adaptive"
                    ANC_MODE_NOISE_CANCELLATION -> "noise_cancellation"
                    else -> "noise_cancellation"
                }

                val resId = pkgContext.resources.getIdentifier(
                    resName, "drawable", "me.kavishdevar.librepods"
                )

                if (resId != 0) {
                    val drawable = pkgContext.resources.getDrawable(resId, pkgContext.theme)
                    button.setImageDrawable(drawable)
                    button.setColorFilter(Color.WHITE)
                } else {
                    button.setImageResource(getIconResourceForMode(mode))
                    button.setColorFilter(Color.WHITE)
                }
            } catch (e: Exception) {
                button.setImageResource(getIconResourceForMode(mode))
                button.setColorFilter(Color.WHITE)
            }
        }

        private fun createAncModeOption(context: Context, mode: Int, isSelected: Boolean, mainButton: ImageButton): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 6, 0, 6)
                }
                gravity = Gravity.CENTER
                setPadding(24, 16, 24, 16)
                tag = "anc_mode_${mode}"

                val icon = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(60, 60).apply {
                        gravity = Gravity.CENTER
                    }
                    tag = "mode_icon_$mode"

                    try {
                        val packageContext = context.createPackageContext(
                            "me.kavishdevar.librepods",
                            Context.CONTEXT_IGNORE_SECURITY
                        )

                        val resourceName = when (mode) {
                            ANC_MODE_OFF -> "noise_cancellation"
                            ANC_MODE_TRANSPARENCY -> "transparency"
                            ANC_MODE_ADAPTIVE -> "adaptive"
                            ANC_MODE_NOISE_CANCELLATION -> "noise_cancellation"
                            else -> "noise_cancellation"
                        }

                        val resourceId = packageContext.resources.getIdentifier(
                            resourceName, "drawable", "me.kavishdevar.librepods"
                        )

                        if (resourceId != 0) {
                            val drawable = packageContext.resources.getDrawable(
                                resourceId, packageContext.theme
                            )
                            setImageDrawable(drawable)
                        } else {
                            setImageResource(getIconResourceForMode(mode))
                        }
                    } catch (e: Exception) {
                        setImageResource(getIconResourceForMode(mode))
                        Log.e(TAG, "Failed to load custom drawable for mode $mode: ${e.message}")
                    }

                    if (isSelected) {
                        setColorFilter(Color.BLACK)
                    } else {
                        setColorFilter(Color.WHITE)
                    }
                }

                addView(icon)

                if (isSelected) {
                    background = createSelectedBackground(context)
                } else {
                    background = null
                }

                setOnClickListener {
                    Log.d(TAG, "ANC mode selected: $mode (was: $currentANCMode)")
                    val container = findAirPodsContainer(this)
                    val drawerContainer = container?.findViewWithTag<View>("airpods_drawer_container")

                    if (currentANCMode == mode) {
                        if (drawerContainer != null && container != null) {
                            hideAirPodsDrawer(container, mainButton, drawerContainer)
                        }
                        return@setOnClickListener
                    }

                    currentANCMode = mode

                    val parentDrawer = parent as? ViewGroup
                    if (parentDrawer != null) {
                        for (i in 0 until parentDrawer.childCount) {
                            val child = parentDrawer.getChildAt(i) as? LinearLayout
                            if (child != null && child.tag.toString().startsWith("anc_mode_")) {
                                val childModeStr = child.tag.toString().substringAfter("anc_mode_")
                                val childMode = childModeStr.toIntOrNull() ?: -1
                                val childIcon = child.findViewWithTag<ImageView>("mode_icon_${childMode}")

                                if (childMode == mode) {
                                    child.background = createSelectedBackground(context)
                                    childIcon?.setColorFilter(Color.BLACK)
                                } else {
                                    child.background = null
                                    childIcon?.setColorFilter(Color.WHITE)
                                }
                            }
                        }
                    }

                    val intent = Intent(ACTION_SET_ANC_MODE).apply {
                        setPackage("me.kavishdevar.librepods")
                        putExtra(EXTRA_ANC_MODE, mode)
                    }
                    context.sendBroadcast(intent)
                    Log.d(TAG, "Sent broadcast to change ANC mode to: ${getLabelForMode(currentANCMode)}")


                    updateMainButtonIcon(context, mainButton, mode)

                    if (drawerContainer != null && container != null) {
                         android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                             hideAirPodsDrawer(container, mainButton, drawerContainer)
                         }, 50)
                    }
                }
            }
        }

        private fun createSelectedBackground(context: Context): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = 50f
            }
        }

        private fun findAirPodsContainer(view: View): ViewGroup? {
            var current: View? = view
            while (current != null) {
                if (current is ViewGroup && current.tag == "airpods_container") {
                    return current
                }
                val parent = current.parent
                if (parent is ViewGroup && parent.tag == "airpods_container") {
                    return parent
                }
                current = parent as? View
            }
            Log.w(TAG, "Could not find airpods_container ancestor")
            return null
        }

        private fun showAirPodsDrawer(container: ViewGroup, mainButton: ImageButton, drawerContainer: View) {
            Log.d(TAG, "Showing AirPods drawer")
            val selectedModeView = drawerContainer.findViewWithTag<View>("anc_mode_$currentANCMode")
            val selectedModeIcon = selectedModeView?.findViewWithTag<ImageView>("mode_icon_$currentANCMode")
            val buttonContainer = container.findViewWithTag<View>("airpods_button_container")

            if (selectedModeView == null || selectedModeIcon == null) {
                Log.e(TAG, "Cannot find selected mode view or icon for show animation")

                drawerContainer.alpha = 0f
                drawerContainer.visibility = View.VISIBLE

                drawerContainer.animate()
                    .alpha(1f)
                    .setDuration(ANIMATION_DURATION)
                    .start()

                buttonContainer?.animate()
                    ?.alpha(0f)
                    ?.setDuration(ANIMATION_DURATION / 2)
                    ?.setStartDelay(ANIMATION_DURATION / 2)
                    ?.withEndAction {
                        buttonContainer.visibility = View.GONE
                    }
                    ?.start()

                return
            }

            drawerContainer.measure(
                View.MeasureSpec.makeMeasureSpec(container.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )

            val drawerHeight = drawerContainer.measuredHeight

            drawerContainer.alpha = 0f
            drawerContainer.visibility = View.VISIBLE
            drawerContainer.translationY = -drawerHeight.toFloat()

            drawerContainer.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(ANIMATION_DURATION)
                .setInterpolator(DecelerateInterpolator())
                .start()

            buttonContainer?.animate()
                ?.alpha(0f)
                ?.setDuration(ANIMATION_DURATION / 2)
                ?.setStartDelay(ANIMATION_DURATION / 3)
                ?.withEndAction {
                    buttonContainer.visibility = View.GONE
                }
                ?.start()
        }

        private fun hideAirPodsDrawer(container: ViewGroup, mainButton: ImageButton, drawerContainer: View) {
            Log.d(TAG, "Hiding AirPods drawer")
            val buttonContainer = container.findViewWithTag<View>("airpods_button_container")

            if (buttonContainer != null && buttonContainer.visibility != View.VISIBLE) {
                buttonContainer.alpha = 0f
                buttonContainer.visibility = View.VISIBLE
            }

            buttonContainer?.animate()
                ?.alpha(1f)
                ?.setDuration(ANIMATION_DURATION / 2)
                ?.start()

            drawerContainer.animate()
                .translationY(-drawerContainer.height.toFloat())
                .alpha(0f)
                .setDuration(ANIMATION_DURATION)
                .setInterpolator(AccelerateInterpolator())
                .setStartDelay(ANIMATION_DURATION / 4)
                .withEndAction {
                    drawerContainer.visibility = View.GONE
                    drawerContainer.translationY = 0f
                }
                .start()
        }

        private fun getIconResourceForMode(mode: Int): Int {
            return when (mode) {
                ANC_MODE_OFF -> android.R.drawable.ic_lock_silent_mode
                ANC_MODE_TRANSPARENCY -> android.R.drawable.ic_lock_silent_mode_off
                ANC_MODE_ADAPTIVE -> android.R.drawable.ic_menu_compass
                ANC_MODE_NOISE_CANCELLATION -> android.R.drawable.ic_lock_idle_charging
                else -> android.R.drawable.ic_lock_silent_mode_off
            }
        }

        private fun getLabelForMode(mode: Int): String {
            return when (mode) {
                ANC_MODE_OFF -> "Off"
                ANC_MODE_TRANSPARENCY -> "Transparency"
                ANC_MODE_ADAPTIVE -> "Adaptive"
                ANC_MODE_NOISE_CANCELLATION -> "Noise Cancellation"
                else -> "Unknown"
            }
        }
    }
}
