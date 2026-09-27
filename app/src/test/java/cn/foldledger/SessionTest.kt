package cn.foldledger

import cn.foldledger.security.*
import org.junit.Assert.*
import org.junit.Test

/** 验证应用会话的默认锁定和后台敏感状态清理。 */
class SessionTest {
    /** 新会话不能带入旧的解锁状态、选中账目或草稿。 */
    @Test fun startsLockedAndNeverRestoresSensitiveData() {
        val session = SessionViewModel()
        assertFalse(session.unlocked)
        assertNull(session.draft)
        assertNull(session.selectedId)
    }
    /** 后台锁定必须清除可见敏感上下文，重新认证后由用户重新选择。 */
    @Test fun backgroundLockErasesDraftSelectionAndSearch() {
        val session = SessionViewModel()
        session.unlocked = true
        session.selectedId = "private-id"
        session.draft = EditDraft(merchant = "private merchant", amount = "99.00")
        session.search = "private merchant"
        session.lock()
        assertFalse(session.unlocked)
        assertNull(session.draft)
        assertNull(session.selectedId)
        assertEquals("", session.search)
    }
}
