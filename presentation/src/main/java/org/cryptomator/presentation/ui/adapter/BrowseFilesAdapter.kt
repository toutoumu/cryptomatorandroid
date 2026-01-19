package org.cryptomator.presentation.ui.adapter

import android.graphics.BitmapFactory
import android.os.PatternMatcher
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.RelativeLayout
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import org.cryptomator.domain.CloudNode
import org.cryptomator.presentation.R
import org.cryptomator.presentation.databinding.ItemBrowseFilesNodeBinding
import org.cryptomator.presentation.intent.ChooseCloudNodeSettings
import org.cryptomator.presentation.intent.ChooseCloudNodeSettings.NavigationMode.BROWSE_FILES
import org.cryptomator.presentation.intent.ChooseCloudNodeSettings.NavigationMode.SELECT_ITEMS
import org.cryptomator.presentation.model.CloudFileModel
import org.cryptomator.presentation.model.CloudFolderModel
import org.cryptomator.presentation.model.CloudNodeModel
import org.cryptomator.presentation.model.ProgressModel
import org.cryptomator.presentation.model.ProgressStateModel.Companion.COMPLETED
import org.cryptomator.presentation.model.comparator.CloudNodeModelDateNewestFirstComparator
import org.cryptomator.presentation.model.comparator.CloudNodeModelDateOldestFirstComparator
import org.cryptomator.presentation.model.comparator.CloudNodeModelNameAZComparator
import org.cryptomator.presentation.model.comparator.CloudNodeModelSizeBiggestFirstComparator
import org.cryptomator.presentation.model.comparator.CloudNodeModelSizeSmallestFirstComparator
import org.cryptomator.presentation.ui.adapter.BrowseFilesAdapter.VaultContentViewHolder
import org.cryptomator.presentation.util.DateHelper
import org.cryptomator.presentation.util.FileIcon
import org.cryptomator.presentation.util.FileSizeHelper
import org.cryptomator.presentation.util.FileUtil
import org.cryptomator.presentation.util.ResourceHelper.Companion.getDrawable
import org.cryptomator.util.SharedPreferencesHandler
import org.cryptomator.util.file.MimeType
import org.cryptomator.util.file.MimeTypes
import org.cryptomator.util.FileViewMode
import javax.inject.Inject

class BrowseFilesAdapter @Inject
constructor(
	private val dateHelper: DateHelper, //
	private val fileSizeHelper: FileSizeHelper, //
	private val fileUtil: FileUtil, //
	private val sharedPreferencesHandler: SharedPreferencesHandler, //
	private val mimeTypes: MimeTypes //
) : RecyclerViewBaseAdapter<CloudNodeModel<*>, BrowseFilesAdapter.ItemClickListener, VaultContentViewHolder, ItemBrowseFilesNodeBinding>(CloudNodeModelNameAZComparator()), FastScrollRecyclerView.SectionedAdapter {

	private var chooseCloudNodeSettings: ChooseCloudNodeSettings? = null
	private var navigationMode: ChooseCloudNodeSettings.NavigationMode? = null
	private var viewMode: FileViewMode = FileViewMode.LIST

	private val isInSelectionMode: Boolean
		get() = chooseCloudNodeSettings != null

	override fun createViewHolder(binding: ItemBrowseFilesNodeBinding, viewType: Int): VaultContentViewHolder {
		return VaultContentViewHolder(binding)
	}

	override fun getItemBinding(inflater: LayoutInflater, parent: ViewGroup?, viewType: Int): ItemBrowseFilesNodeBinding {
		return ItemBrowseFilesNodeBinding.inflate(inflater, parent, false)
	}

	fun addOrReplaceCloudNode(cloudNodeModel: CloudNodeModel<*>) {
		if (contains(cloudNodeModel)) {
			replaceItem(cloudNodeModel)
		} else {
			addItem(cloudNodeModel)
		}
	}

	fun replaceRenamedCloudFile(cloudNode: CloudNodeModel<out CloudNode>) {
		itemCollection.forEach { nodes ->
			if (nodes.javaClass == cloudNode.javaClass && nodes.name == cloudNode.oldName) {
				val position = positionOf(nodes)
				replaceItem(position, cloudNode)
				return
			}
		}
	}

	override fun setCallback(callback: ItemClickListener) {
		this.callback = callback
	}

	fun setChooseCloudNodeSettings(chooseCloudNodeSettings: ChooseCloudNodeSettings?) {
		this.chooseCloudNodeSettings = chooseCloudNodeSettings
	}

	fun updateNavigationMode(navigationMode: ChooseCloudNodeSettings.NavigationMode) {
		this.navigationMode = navigationMode
		if (isNavigationMode(BROWSE_FILES)) {
			itemCollection.forEach { node ->
				node.isSelected = false
			}
		}
		notifyDataSetChanged()
	}

	fun updateViewMode(viewMode: FileViewMode) {
		this.viewMode = viewMode
	}
	
	fun setViewMode(viewMode: FileViewMode) {
		this.viewMode = viewMode
		notifyDataSetChanged()
	}

	fun renderedCloudNodes(): List<CloudNodeModel<*>> {
		return itemCollection
	}

	fun selectedCloudNodes(): List<CloudNodeModel<*>> {
		return all.filter { it.isSelected }
	}

	fun hasUnSelectedNode(): Boolean {
		return itemCount > selectedCloudNodes().size
	}

	fun filterNodes(nodes: List<CloudNodeModel<*>>?, filterText: String): List<CloudNodeModel<*>>? {
		return if (filterText.isNotEmpty()) {
			if (sharedPreferencesHandler.useGlobSearch()) {
				nodes?.filter { cloudNode -> PatternMatcher(filterText, PatternMatcher.PATTERN_SIMPLE_GLOB).match(cloudNode.name) }
			} else {
				nodes?.filter { cloudNode -> cloudNode.name.contains(filterText, true) }
			}
		} else {
			nodes
		}
	}

	inner class VaultContentViewHolder internal constructor(private val binding: ItemBrowseFilesNodeBinding) : RecyclerViewBaseAdapter<CloudNodeModel<*>, BrowseFilesAdapter.ItemClickListener, VaultContentViewHolder, ItemBrowseFilesNodeBinding>.ItemViewHolder(binding.root) {

		private var uiState: UiStateTest? = null

		private var currentProgressIcon: Int = 0

		private var bound: CloudNodeModel<*>? = null

		override fun bind(position: Int) {
			bound = getItem(position)
			bound?.let { internalBind(it) }
		}

		private fun internalBind(node: CloudNodeModel<*>) {
			bindNodeImage(node)
			bindSettings(node)
			bindLongNodeClick(node)
			bindFileOrFolder(node)
			// Apply view mode layout after all binding is complete
			applyViewModeLayout()
		}

		private fun bindNodeImage(node: CloudNodeModel<*>) {
			if (node is CloudFileModel && isImageMediaType(node.name) && node.thumbnail != null) {
				val bitmap = BitmapFactory.decodeFile(node.thumbnail!!.absolutePath)
				binding.cloudNodeImage.setImageBitmap(bitmap)
			} else {
				binding.cloudNodeImage.setImageResource(bindCloudNodeImage(node))
			}
		}

		private fun isImageMediaType(filename: String): Boolean {
			return (mimeTypes.fromFilename(filename) ?: MimeType.WILDCARD_MIME_TYPE).mediatype == "image"
		}

		private fun bindCloudNodeImage(cloudNodeModel: CloudNodeModel<*>): Int {
			if (cloudNodeModel is CloudFileModel) {
				return FileIcon.fileIconFor(cloudNodeModel.name, fileUtil).iconResource
			} else if (cloudNodeModel is CloudFolderModel) {
				return R.drawable.node_folder
			}
			throw IllegalStateException("Could not identify the CloudNodeModel type")
		}

		private fun bindSettings(node: CloudNodeModel<*>) {
			binding.settings.setOnClickListener { callback.onNodeSettingsClicked(node) }
		}

		private fun bindLongNodeClick(node: CloudNodeModel<*>) {
			enableNodeLongClick {
				node.isSelected = true
				callback.onNodeLongClicked()
				true
			}
		}

		private fun bindFileOrFolder(node: CloudNodeModel<*>) {
			if (node is CloudFileModel) {
				internalBind(node)
			} else {
				internalBind(node as CloudFolderModel)
			}
		}

		private fun internalBind(file: CloudFileModel) {
			switchTo(FileDetails())
			bindFile(file)
			bindProgressIfPresent(file)
			bindSelectItemsModeIfPresent(file)
			bindFileSelectionModeIfPresent(file)
		}

		private fun bindFile(file: CloudFileModel) {
			binding.llCloudFileContent.cloudFileText.text = file.name
			binding.llCloudFileContent.cloudFileSubText.text = fileDetails(file)

			enableNodeClick { callback.onFileClicked(file) }
		}

		private fun bindFileSelectionModeIfPresent(file: CloudFileModel) {
			if (isInSelectionMode) {
				disableNodeLongClick()
				hideSettings()
				if (!isSelectable(file)) {
					binding.llCloudFileContent.cloudFileSubText.visibility = GONE
					binding.llCloudFileContent.cloudFileSubText.text = ""
					itemView.isEnabled = false
				}
			}
		}

		private fun internalBind(folder: CloudFolderModel) {
			switchTo(FolderDetails())
			bindFolder(folder)
			bindSelectItemsModeIfPresent(folder)
			bindFolderSelectionModeIfPresent(folder)
			bindProgressIfPresent(folder)
		}

		private fun bindSelectItemsModeIfPresent(node: CloudNodeModel<*>) {
			if (isNavigationMode(SELECT_ITEMS)) {
				if (node is CloudFileModel) {
					switchTo(FileSelection())
				} else {
					switchTo(FolderSelection())
				}
				disableNodeLongClick()
				bindNodeSelection(node)
			}
		}

		private fun bindProgressIfPresent(node: CloudNodeModel<*>) {
			node.progress?.let { showProgress(it) }
		}

		private fun bindFolder(folder: CloudFolderModel) {
			binding.llCloudFolderContent.cloudFolderText.text = folder.name
			enableNodeClick { callback.onFolderClicked(folder) }
		}

		private fun bindFolderSelectionModeIfPresent(folder: CloudFolderModel) {
			if (isInSelectionMode) {
				disableNodeLongClick()
				hideSettings()
				if (!isSelectable(folder)) {
					itemView.isEnabled = false
				}
			}
		}

		private fun hideSettings() {
			binding.settings.visibility = GONE
		}

		private fun bindNodeSelection(cloudNodeModel: CloudNodeModel<*>) {
			binding.itemCheckBox.setOnCheckedChangeListener { _, isChecked ->
				cloudNodeModel.isSelected = isChecked
				callback.onSelectedNodesChanged(selectedCloudNodes().size)
			}
			enableNodeClick { binding.itemCheckBox.toggle() }

			binding.itemCheckBox.isChecked = cloudNodeModel.isSelected
		}

		private fun fileDetails(cloudFile: CloudFileModel): String {
			val formattedFileSize = fileSizeHelper.getFormattedFileSize(cloudFile.size)
			val formattedModifiedDate = dateHelper.getFormattedModifiedDate(cloudFile.modified)

			return if (formattedFileSize != null) {
				if (formattedModifiedDate != null) {
					"$formattedFileSize • $formattedModifiedDate"
				} else {
					formattedFileSize
				}
			} else formattedModifiedDate ?: ""
		}

		fun showProgress(progress: ProgressModel?) {
			bound?.progress = progress
			when {
				progress?.state() === COMPLETED -> hideProgress()
				progress?.progress() == ProgressModel.UNKNOWN_PROGRESS_PERCENTAGE -> showIndeterminateProgress(progress)
				progress?.state() !== COMPLETED -> progress?.let { showDeterminateProgress(it) }
			}
		}

		private fun showIndeterminateProgress(progress: ProgressModel) {
			uiState?.let { switchTo(it.indeterminateProgress()) }
			if (uiState?.isForFile == true) {
				binding.llCloudFileContent.cloudFileSubText.setText(progress.state().textResourceId())
			} else {
				binding.llCloudFolderContent.cloudFolderActionText.setText(progress.state().textResourceId())
			}

			if (!progress.state().isSelectable) {
				disableNodeActions()
			}
		}

		private fun disableNodeActions() {
			itemView.isEnabled = false
			binding.settings.visibility = GONE
		}

		private fun enableNodeClick(clickListener: View.OnClickListener) {
			itemView.setOnClickListener(clickListener)
		}

		private fun enableNodeLongClick(longClickListener: View.OnLongClickListener) {
			itemView.setOnLongClickListener(longClickListener)
		}

		private fun disableNodeLongClick() {
			itemView.setOnLongClickListener(null)
		}

		private fun showDeterminateProgress(progress: ProgressModel) {
			uiState?.let { switchTo(it.determinateProgress()) }
			if (uiState?.isForFile == true) {
				disableNodeActions()
				binding.llCloudFileContent.rlCloudFileProgress.cloudFile.progress = progress.progress()
				if (currentProgressIcon != progress.state().imageResourceId()) {
					currentProgressIcon = progress.state().imageResourceId()
					binding.llCloudFileContent.progressIcon.setImageDrawable(getDrawable(currentProgressIcon))
				}
			} else {
				// no determinate progress for folders
				binding.llCloudFolderContent.cloudFolderActionText.setText(progress.state().textResourceId())
			}
		}

		fun hideProgress() {
			uiState?.let { switchTo(it.details()) }
			bound?.progress = null
		}

		fun replaceImageWithDownloadIcon() {
			binding.cloudNodeImage.setImageResource(R.drawable.ic_file_download)
		}

		private fun switchTo(state: UiStateTest) {
			if (uiState !== state) {
				uiState = state
				uiState?.apply()
			}
		}

		fun selectNode(checked: Boolean) {
			binding.itemCheckBox.isChecked = checked
		}

		abstract inner class UiStateTest(val isForFile: Boolean) {

			fun details(): UiStateTest {
				return if (isForFile) {
					FileDetails()
				} else {
					FolderDetails()
				}
			}

			fun determinateProgress(): UiStateTest {
				return if (isForFile) {
					FileDeterminateProgress()
				} else {
					FolderIndeterminateProgress() // no determinate progress for folders
				}
			}

			fun indeterminateProgress(): UiStateTest {
				return if (isForFile) {
					FileIndeterminateProgress()
				} else {
					FolderIndeterminateProgress()
				}
			}

			abstract fun apply()
		}

		inner class FileDetails : UiStateTest(true) {

			override fun apply() {
				itemView.isEnabled = true
				binding.llCloudFolderContent.cloudFolderContent.visibility = GONE
				binding.llCloudFileContent.cloudFileContent.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileText.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileSubText.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileProgress.visibility = GONE
				binding.settings.visibility = VISIBLE
				binding.itemCheckBox.visibility = GONE
			}
		}

		inner class FolderDetails : UiStateTest(false) {

			override fun apply() {
				itemView.isEnabled = true
				binding.llCloudFileContent.cloudFileContent.visibility = GONE
				binding.llCloudFolderContent.cloudFolderContent.visibility = VISIBLE
				binding.llCloudFolderContent.cloudFolderText.visibility = VISIBLE
				binding.llCloudFolderContent.cloudFolderActionText.visibility = GONE
				binding.settings.visibility = VISIBLE
				binding.itemCheckBox.visibility = GONE
			}
		}

		inner class FileDeterminateProgress : UiStateTest(true) {

			override fun apply() {
				binding.llCloudFolderContent.cloudFolderContent.visibility = GONE
				binding.llCloudFileContent.cloudFileContent.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileText.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileSubText.visibility = GONE
				binding.llCloudFileContent.cloudFileProgress.visibility = VISIBLE
				binding.itemCheckBox.visibility = GONE
			}
		}

		inner class FileIndeterminateProgress : UiStateTest(true) {

			override fun apply() {
				binding.llCloudFolderContent.cloudFolderContent.visibility = GONE
				binding.llCloudFileContent.cloudFileContent.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileText.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileSubText.visibility = VISIBLE
				binding.llCloudFileContent.cloudFileProgress.visibility = GONE
				binding.itemCheckBox.visibility = GONE
			}

		}

		inner class FolderIndeterminateProgress : UiStateTest(false) {

			override fun apply() {
				binding.llCloudFileContent.cloudFileContent.visibility = GONE
				binding.llCloudFolderContent.cloudFolderContent.visibility = VISIBLE
				binding.llCloudFolderContent.cloudFolderText.visibility = VISIBLE
				binding.llCloudFolderContent.cloudFolderActionText.visibility = VISIBLE
				binding.itemCheckBox.visibility = GONE
			}
		}

		inner class FileSelection : UiStateTest(true) {

			override fun apply() {
				binding.itemCheckBox.visibility = VISIBLE
				binding.settings.visibility = GONE
			}
		}

		inner class FolderSelection : UiStateTest(false) {

			override fun apply() {
				binding.itemCheckBox.visibility = VISIBLE
				binding.settings.visibility = GONE
			}

		}

		private fun applyViewModeLayout() {
			when (viewMode) {
				FileViewMode.LIST -> {
					// List mode - restore original layout
					restoreListModeLayout()
				}
				FileViewMode.GRID -> {
					// Grid mode - adjust for grid layout
					applyGridModeLayout()
				}
			}
		}

		private fun restoreListModeLayout() {
			// Remove debug background color
			itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
			
			// Set list mode item height
			val listItemHeight = itemView.context.resources.getDimensionPixelSize(R.dimen.list_item_height)
			itemView.layoutParams.height = listItemHeight
			
			// Restore original thumbnail size and positioning
			val thumbnailSize = itemView.context.resources.getDimensionPixelSize(R.dimen.thumbnail_size)
			val thumbnailParams = binding.cloudNodeImage.layoutParams as RelativeLayout.LayoutParams
			thumbnailParams.width = thumbnailSize
			thumbnailParams.height = thumbnailSize
			// Restore original thumbnail positioning
			thumbnailParams.removeRule(RelativeLayout.CENTER_HORIZONTAL)
			thumbnailParams.addRule(RelativeLayout.CENTER_VERTICAL)
			thumbnailParams.topMargin = 0
			binding.cloudNodeImage.layoutParams = thumbnailParams
			
			// Restore settings button positioning for list mode
			val settingsParams = binding.settings.layoutParams as RelativeLayout.LayoutParams
			settingsParams.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT)
			settingsParams.removeRule(RelativeLayout.ALIGN_PARENT_TOP)
			settingsParams.addRule(RelativeLayout.CENTER_VERTICAL)
			settingsParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
			settingsParams.topMargin = 0
			settingsParams.rightMargin = 0
			binding.settings.layoutParams = settingsParams
			
			// Restore content positioning for list mode
			val folderContentParams = binding.llCloudFolderContent.root.layoutParams as RelativeLayout.LayoutParams
			folderContentParams.removeRule(RelativeLayout.BELOW)
			folderContentParams.removeRule(RelativeLayout.CENTER_HORIZONTAL)
			folderContentParams.addRule(RelativeLayout.CENTER_VERTICAL)
			folderContentParams.addRule(RelativeLayout.RIGHT_OF, R.id.cloud_node_image)
			folderContentParams.addRule(RelativeLayout.LEFT_OF, R.id.controls)
			folderContentParams.topMargin = 0
			binding.llCloudFolderContent.root.layoutParams = folderContentParams
			
			val fileContentParams = binding.llCloudFileContent.root.layoutParams as RelativeLayout.LayoutParams
			fileContentParams.removeRule(RelativeLayout.BELOW)
			fileContentParams.removeRule(RelativeLayout.CENTER_HORIZONTAL)
			fileContentParams.addRule(RelativeLayout.CENTER_VERTICAL)
			fileContentParams.addRule(RelativeLayout.RIGHT_OF, R.id.cloud_node_image)
			fileContentParams.addRule(RelativeLayout.LEFT_OF, R.id.controls)
			fileContentParams.topMargin = 0
			binding.llCloudFileContent.root.layoutParams = fileContentParams
			
			// Restore text alignment and size for list mode
			binding.llCloudFolderContent.cloudFolderText.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
			binding.llCloudFolderContent.cloudFolderText.setTextColor(android.graphics.Color.WHITE) // Original color
			binding.llCloudFolderContent.cloudFolderText.textSize = 16f // Original size
			
			binding.llCloudFileContent.cloudFileText.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
			binding.llCloudFileContent.cloudFileText.setTextColor(android.graphics.Color.WHITE) // Original color
			binding.llCloudFileContent.cloudFileText.textSize = 16f // Original size
			
			binding.llCloudFileContent.cloudFileSubText.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
			
			// Restore visibility for list mode (show original content and file sub text)
			binding.llCloudFileContent.cloudFileContent.visibility = VISIBLE
			binding.llCloudFolderContent.cloudFolderContent.visibility = VISIBLE
			binding.llCloudFileContent.cloudFileSubText.visibility = VISIBLE
			
			// Remove any overlay views added in grid mode
			val parent = itemView as android.view.ViewGroup
			while (parent.childCount > 5) { // Keep only original layout children
				parent.removeViewAt(parent.childCount - 1)
			}
		}

		private fun applyGridModeLayout() {
			val context = itemView.context
			val gridThumbnailSize = context.resources.getDimensionPixelSize(R.dimen.grid_thumbnail_size)
			val padding = context.resources.getDimensionPixelSize(R.dimen.global_padding)
			
			// Remove debug background (now using normal background)
			itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
			
			// Set grid mode item height - thumbnail + filename + date + padding
			val gridItemHeight = gridThumbnailSize + (padding * 2) + 70 // Space for 2 lines of text
			itemView.layoutParams.height = gridItemHeight
			
			// Adjust thumbnail size to be square and centered
			val thumbnailParams = binding.cloudNodeImage.layoutParams as RelativeLayout.LayoutParams
			thumbnailParams.width = gridThumbnailSize
			thumbnailParams.height = gridThumbnailSize
			// Center thumbnail horizontally
			thumbnailParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
			thumbnailParams.removeRule(RelativeLayout.CENTER_VERTICAL)
			thumbnailParams.topMargin = padding / 2
			binding.cloudNodeImage.layoutParams = thumbnailParams
			
			// Position settings button in top-right corner (pCloud style)
			val settingsParams = binding.settings.layoutParams as RelativeLayout.LayoutParams
			settingsParams.removeRule(RelativeLayout.CENTER_VERTICAL)
			settingsParams.removeRule(RelativeLayout.LEFT_OF)
			settingsParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
			settingsParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
			settingsParams.topMargin = padding / 4
			settingsParams.rightMargin = padding / 4
			binding.settings.layoutParams = settingsParams
			
			// Hide original content layouts in grid mode
			binding.llCloudFileContent.cloudFileContent.visibility = GONE
			binding.llCloudFolderContent.cloudFolderContent.visibility = GONE
			
			// Get the data from bound item
			val (fileName, dateInfo) = when (val boundItem = bound) {
				is CloudFileModel -> {
					val formattedDate = dateHelper.getFormattedModifiedDate(boundItem.modified) ?: ""
					boundItem.name to formattedDate
				}
				is CloudFolderModel -> {
					boundItem.name to ""
				}
				else -> "Unknown" to ""
			}
			
			// Remove any existing overlay views
			val parent = itemView as android.view.ViewGroup
			while (parent.childCount > 5) { // Keep only original layout children
				parent.removeViewAt(parent.childCount - 1)
			}
			
			// Calculate column width for text sizing
			val screenWidth = context.resources.displayMetrics.widthPixels
			val gridColumns = 3 // As defined in BrowseFilesFragment
			val columnWidth = screenWidth / gridColumns
			val availableTextWidth = columnWidth - (padding * 2)
			
			// Create filename text view (pCloud style)
			val fileNameView = android.widget.TextView(context)
			fileNameView.id = android.view.View.generateViewId() // Generate unique ID
			
			// Smart text shortening based on column width
			val shortenedFileName = shortenTextForWidth(fileName, availableTextWidth, 12f, context)
			fileNameView.text = shortenedFileName
			
			fileNameView.setTextColor(android.graphics.Color.WHITE)
			fileNameView.textSize = 12f
			fileNameView.gravity = android.view.Gravity.CENTER_HORIZONTAL
			fileNameView.maxLines = 1
			fileNameView.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
			fileNameView.setPadding(padding / 2, 2, padding / 2, 0)
			
			val fileNameParams = android.widget.RelativeLayout.LayoutParams(
				android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
				android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
			)
			fileNameParams.addRule(RelativeLayout.BELOW, R.id.cloud_node_image)
			fileNameParams.topMargin = 4
			fileNameView.layoutParams = fileNameParams
			
			parent.addView(fileNameView)
			
			// Create date text view (only for files, pCloud style)
			if (dateInfo.isNotEmpty()) {
				val dateView = android.widget.TextView(context)
				
				// Apply smart shortening to date text as well
				val shortenedDate = shortenTextForWidth(dateInfo, availableTextWidth, 10f, context)
				dateView.text = shortenedDate
				
				dateView.setTextColor(android.graphics.Color.GRAY)
				dateView.textSize = 10f
				dateView.gravity = android.view.Gravity.CENTER_HORIZONTAL
				dateView.maxLines = 1
				dateView.ellipsize = android.text.TextUtils.TruncateAt.END
				dateView.setPadding(padding / 2, 0, padding / 2, 0)
				
				val dateParams = android.widget.RelativeLayout.LayoutParams(
					android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
					android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
				)
				dateParams.addRule(RelativeLayout.BELOW, fileNameView.id)
				dateView.layoutParams = dateParams
				dateView.id = android.view.View.generateViewId()
				
				parent.addView(dateView)
			}
		}
		
		/**
		 * Intelligently shorten text to fit within the specified width
		 */
		private fun shortenTextForWidth(text: String, availableWidth: Int, textSizeSp: Float, context: android.content.Context): String {
			if (text.isEmpty() || availableWidth <= 0) return text
			
			// Create a paint object to measure text
			val paint = android.graphics.Paint()
			paint.textSize = textSizeSp * context.resources.displayMetrics.scaledDensity
			paint.isAntiAlias = true
			
			// If text fits, return as is
			val originalWidth = paint.measureText(text)
			if (originalWidth <= availableWidth) {
				return text
			}
			
			// Smart shortening strategies
			val extension = if (text.contains('.')) text.substringAfterLast('.', "") else ""
			val nameWithoutExt = if (extension.isNotEmpty()) text.substringBeforeLast('.') else text
			
			// Strategy 1: Try keeping extension and shortening name
			if (extension.isNotEmpty()) {
				val ellipsis = "…"
				val extensionText = ".$extension"
				val extensionWidth = paint.measureText(extensionText)
				val ellipsisWidth = paint.measureText(ellipsis)
				val availableForName = availableWidth - extensionWidth - ellipsisWidth
				
				if (availableForName > 0) {
					// Binary search to find max characters that fit
					var maxChars = 0
					var low = 1
					var high = nameWithoutExt.length
					
					while (low <= high) {
						val mid = (low + high) / 2
						val testText = nameWithoutExt.substring(0, mid)
						val testWidth = paint.measureText(testText)
						
						if (testWidth <= availableForName) {
							maxChars = mid
							low = mid + 1
						} else {
							high = mid - 1
						}
					}
					
					if (maxChars > 0) {
						return nameWithoutExt.substring(0, maxChars) + ellipsis + extensionText
					}
				}
			}
			
			// Strategy 2: Shorten entire text with ellipsis in middle
			val ellipsis = "…"
			val ellipsisWidth = paint.measureText(ellipsis)
			val availableForText = availableWidth - ellipsisWidth
			
			if (availableForText > 0) {
				// Binary search for maximum characters
				var maxChars = 0
				var low = 2 // Minimum 1 char on each side
				var high = text.length - 1
				
				while (low <= high) {
					val mid = (low + high) / 2
					val halfChars = mid / 2
					val testText = text.substring(0, halfChars) + text.substring(text.length - (mid - halfChars))
					val testWidth = paint.measureText(testText)
					
					if (testWidth <= availableForText) {
						maxChars = mid
						low = mid + 1
					} else {
						high = mid - 1
					}
				}
				
				if (maxChars >= 2) {
					val halfChars = maxChars / 2
					return text.substring(0, halfChars) + ellipsis + text.substring(text.length - (maxChars - halfChars))
				}
			}
			
			// Fallback: Return first character + ellipsis
			return if (text.isNotEmpty()) text.substring(0, 1) + "…" else text
		}

	}

	private fun isSelectable(folder: CloudFolderModel): Boolean {
		return chooseCloudNodeSettings?.selectionMode()?.allowsFolders() == true //
				&& chooseCloudNodeSettings?.excludeFolder(folder) == false
	}

	private fun isSelectable(file: CloudFileModel): Boolean {
		return chooseCloudNodeSettings?.selectionMode()?.allowsFiles() == true //
				&& chooseCloudNodeSettings?.namePattern()?.matcher(file.name)?.matches() == true
	}

	private fun isNavigationMode(navigationMode: ChooseCloudNodeSettings.NavigationMode): Boolean {
		return this.navigationMode == navigationMode
	}

	fun setSort(comparator: Comparator<CloudNodeModel<*>>) {
		updateComparator(comparator)
	}

	interface ItemClickListener {

		fun onFolderClicked(cloudFolderModel: CloudFolderModel)

		fun onFileClicked(cloudNodeModel: CloudFileModel)

		fun onNodeSettingsClicked(cloudNodeModel: CloudNodeModel<*>)

		fun onNodeLongClicked()

		fun onSelectedNodesChanged(selectedNodes: Int)
	}

	override fun getSectionName(position: Int): String {
		val node = all[position]

		if (node.isFolder) {
			return node.name.first().toString()
		}

		val formattedFileSize = fileSizeHelper.getFormattedFileSize((node as CloudFileModel).size)
		val formattedModifiedDate = dateHelper.getFormattedModifiedDate(node.modified)

		return when (comparator) {
			is CloudNodeModelDateNewestFirstComparator, is CloudNodeModelDateOldestFirstComparator -> formattedModifiedDate ?: node.name.first().toString()
			is CloudNodeModelSizeBiggestFirstComparator, is CloudNodeModelSizeSmallestFirstComparator -> formattedFileSize ?: node.name.first().toString()
			else -> all[position].name.first().toString()
		}
	}
}
