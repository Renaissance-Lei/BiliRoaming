package me.iacn.biliroaming.hook

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*
import java.lang.reflect.Field
import java.util.WeakHashMap

class OfflineCacheSearchHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    companion object {
        // Set to false to stop enabling title search by default on first open.
        private const val DEFAULT_ENABLE_TITLE_SEARCH = true

        // Set to true to also enable author-name search by default.
        private const val DEFAULT_ENABLE_AUTHOR_SEARCH = false

        // Increase for looser spacing between the two checkboxes.
        private const val OPTION_HORIZONTAL_SPACING_DP = 12

        private const val OPTION_CONTAINER_TAG = "biliroaming_offline_search_options"
        private val hostStates = WeakHashMap<Any, HostState>()
        private val watchedInputs = WeakHashMap<EditText, Unit>()

        private val titleMethodNames = arrayOf(
            "getTitle",
            "getShowTitle",
            "getDisplayTitle",
            "getDownloadTitle",
            "getVideoTitle"
        )
        private val titleFieldNames = arrayOf(
            "title",
            "mTitle",
            "title_",
            "show_title",
            "showTitle",
            "download_title",
            "downloadTitle"
        )
        private val authorMethodNames = arrayOf(
            "getAuthor",
            "getAuthorName",
            "getOwnerName",
            "getUpperName",
            "getUpName",
            "getUperName",
            "getUname"
        )
        private val authorFieldNames = arrayOf(
            "author",
            "author_",
            "author_name",
            "owner_name",
            "ownerName",
            "up_name",
            "upName",
            "upperName",
            "mAuthor",
            "uname"
        )
        private val nestedNameMethodNames = arrayOf("getName", "getTitle", "getNickName", "getUname")
        private val nestedNameFieldNames = arrayOf("name", "title", "uname", "nickName")
    }

    override fun startHook() {
        Log.d("startHook: OfflineCacheSearch")
        listOfNotNull(
            "tv.danmaku.bili.ui.offline.DownloadingActivity".findClassOrNull(mClassLoader),
            "tv.danmaku.bili.ui.offline.HdOfflineDowningFragment".findClassOrNull(mClassLoader)
        ).forEach { hostClass ->
            hostClass.hookAfterMethod("onResume") { param ->
                installSearchEnhancer(param.thisObject)
            }
        }
    }

    private fun installSearchEnhancer(host: Any) {
        val rootView = resolveRootView(host) ?: return
        rootView.post {
            val state = hostStates.getOrPut(host) { HostState() }
            val searchInput = findSearchInput(rootView) ?: return@post
            val listView = findListView(rootView) ?: return@post

            state.searchInput = searchInput
            state.listView = listView

            ensureSearchWatcher(state, searchInput)
            ensureOptionContainer(state, searchInput)

            if (searchInput.text.isNullOrEmpty()) {
                snapshotOriginalItems(state)
            }
            applyFilter(state)
        }
    }

    private fun resolveRootView(host: Any): View? {
        return when (host) {
            is Activity -> host.findViewById(android.R.id.content)
            else -> host.callMethodOrNullAs<View>("getView")
        }
    }

    private fun ensureSearchWatcher(state: HostState, searchInput: EditText) {
        if (watchedInputs.containsKey(searchInput)) return
        watchedInputs[searchInput] = Unit
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                searchInput.post {
                    if (searchInput.text.isNullOrEmpty()) {
                        snapshotOriginalItems(state)
                    }
                    applyFilter(state)
                }
            }
        })
    }

    private fun ensureOptionContainer(state: HostState, searchInput: EditText) {
        val searchBar = (searchInput.parent as? View) ?: searchInput
        val parent = searchBar.parent as? ViewGroup ?: return
        val optionContainer = parent.findViewWithTag<LinearLayout>(OPTION_CONTAINER_TAG)
            ?: LinearLayout(searchInput.context).apply {
                tag = OPTION_CONTAINER_TAG
                orientation = LinearLayout.HORIZONTAL
                setPadding(16.dp, 0, 16.dp, 8.dp)

                val titleCheckBox = createOptionCheckBox(
                    context,
                    "\u6807\u9898",
                    DEFAULT_ENABLE_TITLE_SEARCH
                )
                val authorCheckBox = createOptionCheckBox(
                    context,
                    "\u535a\u4e3b",
                    DEFAULT_ENABLE_AUTHOR_SEARCH
                ).apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.marginStart =
                        OPTION_HORIZONTAL_SPACING_DP.dp
                }

                addView(titleCheckBox)
                addView(authorCheckBox)

                val insertIndex = parent.indexOfChild(searchBar).let { index ->
                    if (index == -1) parent.childCount else index + 1
                }
                parent.addView(this, insertIndex)
            }

        val titleCheckBox = optionContainer.getChildAt(0) as? CheckBox ?: return
        val authorCheckBox = optionContainer.getChildAt(1) as? CheckBox ?: return
        state.titleCheckBox = titleCheckBox
        state.authorCheckBox = authorCheckBox

        var internalChange = false
        val checkedListener = checkedListener@{ button: CheckBox ->
            if (internalChange) return@checkedListener

            // Keep at least one option enabled, otherwise every query becomes meaningless.
            if (!titleCheckBox.isChecked && !authorCheckBox.isChecked) {
                internalChange = true
                button.isChecked = true
                internalChange = false
                return@checkedListener
            }

            searchInput.post { applyFilter(state) }
        }

        titleCheckBox.setOnCheckedChangeListener { _, _ -> checkedListener(titleCheckBox) }
        authorCheckBox.setOnCheckedChangeListener { _, _ -> checkedListener(authorCheckBox) }
    }

    private fun createOptionCheckBox(context: Context, text: String, checked: Boolean): CheckBox {
        return CheckBox(context).apply {
            this.text = text
            isChecked = checked
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun snapshotOriginalItems(state: HostState) {
        val binding = resolveAdapterBinding(state) ?: return
        state.originalItems = ArrayList(binding.items)
    }

    private fun applyFilter(state: HostState) {
        val binding = resolveAdapterBinding(state) ?: return
        val query = state.searchInput?.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            binding.updateItems(state.originalItems)
            return
        }

        val titleEnabled = state.titleCheckBox?.isChecked ?: DEFAULT_ENABLE_TITLE_SEARCH
        val authorEnabled = state.authorCheckBox?.isChecked ?: DEFAULT_ENABLE_AUTHOR_SEARCH
        val sourceItems = if (state.originalItems.isNotEmpty()) state.originalItems else binding.items
        val filteredItems = sourceItems.filter {
            it.matchesOfflineQuery(query, titleEnabled, authorEnabled)
        }
        binding.updateItems(filteredItems)
    }

    private fun resolveAdapterBinding(state: HostState): AdapterBinding? {
        val listView = state.listView ?: return null
        val adapter = listView.callMethodOrNull("getAdapter") ?: return null
        if (state.adapterClassName != adapter.javaClass.name) {
            state.adapterClassName = adapter.javaClass.name
            state.backingFieldName = null
        }

        val field = state.backingFieldName?.let { fieldName ->
            adapter.javaClass.findField {
                it.name == fieldName && List::class.java.isAssignableFrom(it.type)
            }
        } ?: adapter.findBestListField()?.also { state.backingFieldName = it.name }

        val items = field?.let { getFieldList(adapter, it) } ?: return null
        return AdapterBinding(adapter, field, items, listView)
    }

    private fun Any.findBestListField(): Field? {
        return javaClass.allFields()
            .filter { List::class.java.isAssignableFrom(it.type) }
            .maxByOrNull { field ->
                val value = getFieldList(this, field)
                val searchableCount = value.count { item ->
                    unwrapDownloadItem(item) != null || item.extractTitle().isNotBlank()
                }
                val mutableScore = if (runCatchingOrNull {
                        field.isAccessible = true
                        field.get(this)
                    } is MutableList<*>
                ) 2 else 0
                val nameScore = if (
                    field.name.contains("list", true) || field.name.contains("data", true)
                ) 1 else 0
                searchableCount * 10 + value.size + mutableScore + nameScore
            }
    }

    private fun getFieldList(target: Any, field: Field): MutableList<Any?> {
        field.isAccessible = true
        val value = field.get(target)
        @Suppress("UNCHECKED_CAST")
        return when (value) {
            is MutableList<*> -> value as MutableList<Any?>
            is List<*> -> ArrayList(value as List<Any?>)
            else -> mutableListOf()
        }
    }

    private fun Field.setListValue(target: Any, items: List<Any?>) {
        isAccessible = true
        val currentValue = get(target)
        when (currentValue) {
            is MutableList<*> -> {
                @Suppress("UNCHECKED_CAST")
                (currentValue as MutableList<Any?>).run {
                    clear()
                    addAll(items)
                }
            }

            else -> set(target, ArrayList(items))
        }
    }

    private fun AdapterBinding.updateItems(items: List<Any?>) {
        field.setListValue(adapter, items)
        adapter.callMethodOrNull("notifyDataSetChanged")
        listView.callMethodOrNull("invalidateViews")
    }

    private fun findSearchInput(rootView: View): EditText? {
        return rootView.allChildViews()
            .filterIsInstance<EditText>()
            .maxByOrNull { editText ->
                val hint = editText.hint?.toString().orEmpty()
                val idName = runCatchingOrNull {
                    editText.resources.getResourceEntryName(editText.id)
                }.orEmpty()
                var score = 0
                if (editText.visibility == View.VISIBLE) score += 1
                if (idName.contains("search", true)) score += 3
                if (hint.contains("\u641c\u7d22", true) || hint.contains("search", true)) score += 2
                score
            }
    }

    private fun findListView(rootView: View): View? {
        return rootView.allChildViews()
            .filter { candidate -> candidate.callMethodOrNull("getAdapter") != null }
            .maxByOrNull { view ->
                val adapter = view.callMethodOrNull("getAdapter")
                val field = adapter?.let { it.findBestListField() }
                val size = field?.let { getFieldList(adapter, it).size } ?: 0
                val nameScore = when {
                    view.javaClass.name.contains("RecyclerView") -> 3
                    view.javaClass.name.contains("ListView") -> 2
                    else -> 0
                }
                size + nameScore
            }
    }

    private fun View.allChildViews(): Sequence<View> = sequence {
        yield(this@allChildViews)
        if (this@allChildViews is ViewGroup) {
            for (index in 0 until childCount) {
                yieldAll(getChildAt(index).allChildViews())
            }
        }
    }

    private fun Any?.matchesOfflineQuery(
        query: String,
        matchTitle: Boolean,
        matchAuthor: Boolean
    ): Boolean {
        if (this == null) return false
        if (matchTitle && extractTitle().contains(query, true)) return true
        if (matchAuthor && extractAuthor().contains(query, true)) return true
        return false
    }

    private fun Any?.extractTitle(): String {
        val target = unwrapDownloadItem(this) ?: this ?: return ""
        return target.readStringByMethods(titleMethodNames)
            ?: target.readStringByFields(titleFieldNames)
            ?: target.toString()
    }

    private fun Any?.extractAuthor(): String {
        val target = unwrapDownloadItem(this) ?: this ?: return ""
        return target.readStringByMethods(authorMethodNames)
            ?: target.readStringByFields(authorFieldNames)
            ?: target.readNestedString(authorFieldNames)
            ?: ""
    }

    private fun unwrapDownloadItem(item: Any?): Any? {
        item ?: return null
        val entryClass = instance.videoDownloadEntryClass
        if (entryClass?.isInstance(item) == true) return item

        return item.javaClass.allFields().firstNotNullOfOrNull { field ->
            runCatchingOrNull {
                field.isAccessible = true
                field.get(item)?.takeIf { value -> entryClass?.isInstance(value) == true }
            }
        }
    }

    private fun Any.readStringByMethods(names: Array<String>): String? {
        return names.firstNotNullOfOrNull { name ->
            callMethodOrNull(name)?.stringValue()
        }?.takeIf { it.isNotBlank() }
    }

    private fun Any.readStringByFields(names: Array<String>): String? {
        return names.firstNotNullOfOrNull { name ->
            javaClass.findField { it.name.equals(name, true) }?.let { field ->
                runCatchingOrNull {
                    field.isAccessible = true
                    field.get(this).stringValue()
                }
            }
        }?.takeIf { it.isNotBlank() }
    }

    private fun Any.readNestedString(names: Array<String>): String? {
        return names.firstNotNullOfOrNull { name ->
            javaClass.findField { it.name.equals(name, true) }?.let { field ->
                val nestedObject = runCatchingOrNull {
                    field.isAccessible = true
                    field.get(this)
                } ?: return@let null

                nestedObject.readStringByMethods(nestedNameMethodNames)
                    ?: nestedObject.readStringByFields(nestedNameFieldNames)
            }
        }?.takeIf { it.isNotBlank() }
    }

    private fun Any?.stringValue(): String? {
        return when (this) {
            null -> null
            is String -> this
            else -> toString()
        }?.trim()
    }

    private fun Class<*>.allFields(): Sequence<Field> = sequence {
        var current: Class<*>? = this@allFields
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { yield(it) }
            current = current.superclass
        }
    }

    private data class HostState(
        var searchInput: EditText? = null,
        var listView: View? = null,
        var titleCheckBox: CheckBox? = null,
        var authorCheckBox: CheckBox? = null,
        var adapterClassName: String? = null,
        var backingFieldName: String? = null,
        var originalItems: MutableList<Any?> = mutableListOf()
    )

    private data class AdapterBinding(
        val adapter: Any,
        val field: Field,
        val items: MutableList<Any?>,
        val listView: View
    )
}
