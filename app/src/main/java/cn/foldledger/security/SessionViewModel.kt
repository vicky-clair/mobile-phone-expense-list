package cn.foldledger.security

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import cn.foldledger.domain.Direction

/** 仅驻留内存的编辑草稿；旋转/折叠时保留，进入后台或进程终止后不恢复。 */
data class EditDraft(val id: String? = null, val amount: String = "", val merchant: String = "",
    val category: String = "其他", val direction: Direction = Direction.EXPENSE,
    val dateTime: String = java.time.LocalDateTime.now().withSecond(0).withNano(0).toString())

/** 界面会话与导航状态；认证默认锁定，不把解锁状态写入持久存储。 */
class SessionViewModel : ViewModel() {
    var unlocked by mutableStateOf(false)
    var selectedId by mutableStateOf<String?>(null)
    var draft by mutableStateOf<EditDraft?>(null)
    var tab by mutableStateOf(0)
    var filter by mutableStateOf("ALL")
    var search by mutableStateOf("")
    var page by mutableIntStateOf(0)
    /** 后台锁定时立即清理草稿、选中账目和搜索内容，避免未经认证继续展示敏感数据。 */
    fun lock() {
        unlocked = false
        selectedId = null
        draft = null
        search = ""
    }
}
