package com.lianwenqi.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.entity.AgentModelRequest
import com.lianwenqi.app.agent.data.entity.AgentModelRequestStatus
import com.lianwenqi.app.agent.data.entity.AgentRecoveryLog
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** F7：Room v30 → v31 迁移；旧任务、旧写入操作完整保留，新执行字段默认未占用 */
@RunWith(AndroidJUnit4::class)
class AgentRuntimeMigrationTest {

    private lateinit var context: Context
    private lateinit var dbName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "f7-runtime-migration-${UUID.randomUUID()}.db"
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migratesV30ToV31AndPreservesOldData() = runBlocking {
        AgentMigrationFixture.createV30Database(context, dbName)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_30_31, AppDatabase.MIGRATION_31_32)
            .allowMainThreadQueries()
            .build()
        try {
            val task = db.agentTaskDao().getTask(1)
            assertNotNull(task)
            assertEquals(AgentTaskStatus.WAITING_CONFIRMATION, task!!.status)
            assertEquals("旧任务", task.title)
            assertEquals("", task.executionOwner)
            assertEquals(null, task.executionLeaseUntil)
            assertEquals(0, task.executionAttempt)
            assertEquals(0, task.executionCursor)
            assertEquals("", task.executionRunId)
            assertFalse(task.pauseRequested)
            assertFalse(task.cancelRequested)
            assertEquals(0, task.recoveryCount)

            val operation = db.agentTaskDao().getWriteOperation(1)
            assertNotNull(operation)
            assertEquals(
                AgentWriteOperationStatus.WAITING_CONFIRMATION,
                operation!!.executionStatus
            )

            assertTrue(tableExists(db, "agent_model_request"))
            assertTrue(tableExists(db, "agent_recovery_log"))
            assertTrue(indexExists(db, "index_agent_model_request_requestKey"))
            assertTrue(indexExists(db, "index_agent_recovery_log_taskId_createdAt"))

            val requestId = db.agentTaskDao().insertModelRequest(
                AgentModelRequest(
                    taskId = 1,
                    requestKey = "r1:1:0:1",
                    status = AgentModelRequestStatus.STARTED
                )
            )
            assertTrue(requestId > 0)
            val logId = db.agentTaskDao().insertRecoveryLog(
                AgentRecoveryLog(taskId = 1, reason = "TEST")
            )
            assertTrue(logId > 0)

            assertEquals("旧书", db.bookDao().getById(1)!!.title)
            assertEquals("旧正文", db.chapterDao().getById(1)!!.content)
        } finally {
            db.close()
        }
    }

    private fun tableExists(db: AppDatabase, name: String): Boolean =
        db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(name)
        ).use { it.moveToFirst() }

    private fun indexExists(db: AppDatabase, name: String): Boolean =
        db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(name)
        ).use { it.moveToFirst() }
}
