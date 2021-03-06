package com.nextfaze.devfun.core

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.support.annotation.RestrictTo
import android.support.v7.app.AlertDialog
import com.nextfaze.devfun.annotations.DeveloperCategory
import com.nextfaze.devfun.annotations.DeveloperFunction
import com.nextfaze.devfun.core.loader.DefinitionsLoader
import com.nextfaze.devfun.core.loader.ModuleLoader
import com.nextfaze.devfun.generated.DevFunGenerated
import com.nextfaze.devfun.inject.*
import com.nextfaze.devfun.internal.*
import kotlin.reflect.KClass

/**
 * Used to automatically initialize [DevFun] without user input.
 *
 * If you want to manually initialize DevFun, remove the node using standard Android manifest merger syntax:
 * ```xml
 * <manifest xmlns:android="http://schemas.android.com/apk/res/android"
 *           xmlns:tools="http://schemas.android.com/tools">
 *
 *     <application>
 *         <!-- This will stop the provider node from being included -->
 *         <provider android:name="com.nextfaze.devfun.core.DevFunInitializerProvider"
 *                   android:authorities="*"
 *                   tools:ignore="ExportedContentProvider"
 *                   tools:node="remove"/>
 *     </application>
 * </manifest>
 * ```
 */
class DevFunInitializerProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        // Create instance so we can manipulate it in user-code before initialize runs
        _devFun = DevFun()

        // Post to next loop; initialize after Application.onCreate so logging frameworks etc. are ready
        Handler(Looper.getMainLooper()).postAtFrontOfQueue { _devFun?.initialize(context) }
        return true
    }

    override fun getType(uri: Uri) = "none/none"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw UnsupportedOperationException("Query not supported")
    override fun insert(uri: Uri, values: ContentValues): Uri = throw UnsupportedOperationException("Insert not supported")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Delete not supported")
    override fun update(uri: Uri, values: ContentValues, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Update not supported")
}

/**
 * Currently active/initialized instance of [DevFun]
 *
 * Use [isDevFunInitialized] to check if this is safe to call.
 *
 * @throws IllegalStateException if [DevFun.initialize] has not been called.
 */
val devFun: DevFun get() = _devFun ?: throw IllegalStateException("DevFun not initialized!")
@SuppressLint("StaticFieldLeak")
private var _devFun: DevFun? = null

/**
 * Flag indicating if DevFun has been initialized yet.
 *
 * i.e. is it safe to use [devFun].
 *
 * @see DevFun.isInitialized
 */
val isDevFunInitialized get() = _devFun?.isInitialized ?: false

/**
 * Callback signature if/when [DevFun] has been initialized.
 *
 * @see [DevFun.initialize]
 * @see [isDevFunInitialized]
 */
typealias OnInitialized = DevFun.() -> Unit

/**
 * Primary entry point and initializer of DevFun and associated libraries.
 *
 * Modules can be added post- initialization by way of `devFun += SomeModule()` ([plusAssign]), after which [tryInitModules] should be called.
 *
 * To manually initialize, create instance and call [initialize].
 * A static reference will be set to this automatically, and can be retrieved using [devFun].
 *
 * e.g. `DevFun().initialize(applicationContext)`
 */
@DeveloperCategory("DevFun", order = 100_000)
class DevFun {
    init {
        devFunVerbose = BuildConfig.VERSION_SNAPSHOT
    }

    private val log = logger()
    private val activityTracker = ActivityTracker()
    private val moduleLoader = ModuleLoader(this)
    private val definitionsLoader = DefinitionsLoader(this)
    private val initializationCallbacks = mutableListOf<OnInitialized>()

    /**
     * Composite list of all [InstanceProvider]s.
     *
     * Add instance providers using [CompositeInstanceProvider.plusAssign] `devFun.instanceProviders += MyInstanceProvider()`
     *
     * Providers are checked in reverse order.
     * i.e. Most recently added are checked first.
     *
     * @see get
     * @see instanceOf
     */
    val instanceProviders = CompositeInstanceProvider()

    private var _application: Application? = null

    /**
     * Context used to initialize DevFun.
     *
     * As of writing, will always be [Context.getApplicationContext].
     *
     * @see initialize
     */
    val context get() = _application ?: throw IllegalStateException("DevFun not initialized!")

    /**
     * Flag indicating if this instance of DevFun has been initialized.
     *
     * @see initialize
     */
    val isInitialized get() = _application != null

    /**
     * Initialize the static [devFun] reference to `this`, [context] to [Context.getApplicationContext], build
     * [instanceProviders], call module's [DevFunModule.initialize], and calls any [initializationCallbacks].
     *
     * Can be called any number of times (will no-op more than once).
     *
     * If using more than one `DevFun` instance, [dispose] should be called.
     */
    fun initialize(context: Context, vararg modules: DevFunModule, useServiceLoader: Boolean = true) {
        if (isInitialized) return

        _devFun = this
        _application = context.applicationContext as Application

        activityTracker.init(_application as Application)
        instanceProviders.apply {
            this += ConstructingInstanceProvider(instanceProviders)
            this += KObjectInstanceProvider()
            this += captureInstance { this@DevFun }
            this += captureInstance<InstanceProvider> { this }
            this += captureInstance<RequiringInstanceProvider> { this }
            this += captureInstance { this }
            this += captureInstance { definitionsLoader }
            this += captureInstance { activityTracker }
            this += captureInstance { { activityTracker.activity } }
            this += AndroidInstanceProvider(context.applicationContext, activityTracker::activity)
            this += moduleLoader
        }

        moduleLoader.init(modules.toList(), useServiceLoader)
        tryInitModules()

        initializationCallbacks.forEach { it() }
        initializationCallbacks.clear()
    }

    /**
     * Add a module.
     *
     * Can be called at any time. Call [tryInitModules] after adding a module.
     */
    operator fun plusAssign(module: DevFunModule) {
        moduleLoader += module
    }

    /**
     * Attempts to initialize uninitialized modules.
     *
     * Can be called any number of times.
     *
     * Can also be called at any time as long as the module's initialization function [DevFunModule.initialize]
     * doesn't try to use [devFun]. [initialize] should be called called before this to be safe.
     *
     * Modules without dependencies will fail to initialize. Add them and call this again.
     */
    fun tryInitModules() = moduleLoader.tryInitModules()

    /**
     * Disposes initialized modules and clears self (static), context, and module references.
     *
     * This instance should not be used again after this.
     */
    fun dispose() { // todo test me
        moduleLoader.dispose()
        instanceProviders.clear()
        activityTracker.dispose(context)
        _devFun = null
        _application = null
    }

    /**
     * Get an instance of a class using [instanceProviders].
     *
     * Intended for use in Kotlin code.
     *
     * @see instanceOf
     * @see InstanceProvider
     *
     * @throws ClassInstanceNotFoundException When [T] could not be found/instantiated and all providers have been checked.
     */
    inline fun <reified T : Any> get() = instanceProviders[T::class]

    /**
     * Get an instance of a class using [instanceProviders].
     *
     * Intended for use in Java code, or when type erasure prohibits use of [get].
     *
     * @see get
     * @see InstanceProvider
     *
     * @throws ClassInstanceNotFoundException When [clazz] could not be found/instantiated and all providers have been checked.
     */
    fun <T : Any> instanceOf(clazz: KClass<out T>) = instanceProviders[clazz]

    /**
     * Add an initialization callback.
     *
     * Will be called immediately if [isInitialized].
     *
     * References to callbacks will not be held after it has been called.
     *
     * @see initialize
     * @see minusAssign
     */
    operator fun plusAssign(onInitialized: OnInitialized) {
        if (isInitialized) {
            onInitialized()
        } else {
            initializationCallbacks += onInitialized
        }
    }

    /**
     * Remove an initialization callback.
     *
     * All callbacks are cleared after initialization, thus this is only necessary if not [isInitialized].
     *
     * @see initialize
     * @see plusAssign
     */
    operator fun minusAssign(onInitialized: OnInitialized) {
        initializationCallbacks -= onInitialized
    }

    /**
     * Processed list of [DevFunGenerated] definitions - transformed, filtered, sorted, etc.
     *
     * This is the list that should be used to display/reference items.
     *
     * @see definitions
     */
    val categories: List<CategoryItem> get() {
        try {
            val transformations = TRANSFORMERS.map { instanceOf(it) }

            // generate missing categories
            val classCategories = definitionsLoader.definitions
                    .flatMap { it.categoryDefinitions }
                    .toSet()
                    .associateBy { it.clazz }
                    .toMutableMap()
            val functionCategories = mutableListOf<CategoryDefinition>()

            // transform function items to menu items
            val funItems = HashSet<FunctionItem>()
            definitionsLoader.definitions
                    .flatMap { it.functionDefinitions }
                    .toSet()
                    .forEach functionItems@ { func ->
                        log.t { "Processing ${func.clazz.simpleName}::${func.name}" }
                        transformations.forEach {
                            if (it.accept(func)) {
                                val funcClass = if (func.clazz.isCompanion) func.clazz.java.enclosingClass.kotlin else func.clazz
                                val classCat = classCategories.getOrPut(funcClass) { SimpleCategoryDefinition(funcClass) }
                                val cat = func.category.let resolveCategory@ { funCat ->
                                    when (funCat) {
                                        null -> classCat
                                        else -> InheritingCategoryDefinition(classCat, funCat).also {
                                            functionCategories += it
                                        }
                                    }
                                }

                                val items = it.apply(func, cat)
                                log.t { "Transformer $it accepted item and returned ${items?.size} items: ${items?.joinToString { it.name }}" }
                                if (items != null) {
                                    funItems.addAll(items)
                                    return@functionItems
                                }
                            } else {
                                log.t { "Transformer $it ignored item" }
                            }
                        }
                    }

            // generate and sort menu categories/items
            return funItems
                    .groupBy {
                        // determine category name for item
                        it.category.name ?: it.category.clazz?.splitSimpleName ?: "Misc"
                    }
                    .mapKeys { (categoryName, functionItems) ->
                        // create category object for items and determine order
                        val order = functionItems.firstOrNull { it.category.order != null }?.category?.order ?: 0
                        SimpleCategory(categoryName, functionItems, order)
                    }
                    .keys
                    .sortedWith(compareBy<SimpleCategory> { it.order }.thenBy { it.name })
        } catch (t: Throwable) {
            log.w(t) { "Exception generating categories." }
            return listOf(ExceptionCategoryItem(t.stackTraceAsString))
        }
    }

    /**
     * List of raw [DevFunGenerated] definitions.
     *
     * In general this should not be used - use [categories] instead.
     *
     * __Be aware that items in this list may be invalid for use on this device (e.g. unsupported SDK, invalid context, etc.)__
     *
     * @internal Visible for testing - use at your own risk.
     */
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    val definitions get() = definitionsLoader.definitions

    @DeveloperFunction
    private fun about(activity: Activity) {
        AlertDialog.Builder(activity)
                .setTitle(R.string.df_devfun)
                .setMessage(activity.getString(R.string.df_devfun_about, BuildConfig.VERSION_NAME))
                .show()
    }
}

private data class SimpleCategory(override val name: String,
                                  override val items: List<FunctionItem>,
                                  override val order: Int = 0) : CategoryItem

private data class SimpleCategoryDefinition(override val clazz: KClass<*>) : CategoryDefinition {
    override val name: String get() = clazz.splitSimpleName
}

private data class InheritingCategoryDefinition(val parent: CategoryDefinition, val child: CategoryDefinition) : CategoryDefinition {
    override val clazz: KClass<*>? get() = child.clazz ?: parent.clazz
    override val name: String? get() = child.name ?: parent.name
    override val group: String? get() = child.group ?: parent.group
    override val order: Int? get() = child.order ?: parent.order
}
