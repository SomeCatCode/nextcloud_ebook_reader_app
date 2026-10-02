package com.somecatcode.ebookreader.data.account

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.somecatcode.ebookreader.data.db.BookEntity
import com.somecatcode.ebookreader.data.newDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AccountStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val db = newDatabase(ApplicationProvider.getApplicationContext())
    private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmCredentialCipher { key }
    private var ids = 0
    private lateinit var store: AccountStoreImpl
    private lateinit var credentialsDir: java.io.File
    private lateinit var booksRoot: java.io.File
    private val cancelled = mutableListOf<String>()

    @Before
    fun setUp() {
        credentialsDir = tmp.newFolder("creds")
        booksRoot = tmp.newFolder("books")
        store = AccountStoreImpl(
            db, cipher, credentialsDir, { booksRoot },
            clock = { 42 }, newId = { "acc-${++ids}" }, beforeRemove = { cancelled += it },
        )
    }

    @After
    fun tearDown() = db.close()

    private fun newAccount(user: String = "alice", server: String = "https://cloud.example.org/") = NewAccount(
        serverUrl = server, loginName = user, appPassword = "s3cret-app-pw", userId = user,
        displayName = "Alice", serverVersion = "31.0.1", appVersion = null,
    )

    @Test
    fun addStoresMetadataAndEncryptedPassword() = runBlocking {
        val account = store.add(newAccount())
        assertEquals("acc-1", account.id)
        assertEquals("https://cloud.example.org", account.serverUrl)
        assertEquals(listOf(account), store.list())
        assertEquals(listOf(account), store.accounts.first())

        val creds = store.credentials(account.id)!!
        assertEquals("alice", creds.loginName)
        assertEquals("s3cret-app-pw", creds.appPassword)
        assertFalse(creds.toString().contains("s3cret"))

        val file = credentialsDir.listFiles()!!.single { it.name.endsWith(".enc") }
        assertFalse("password must not be stored in clear", file.readText().contains("s3cret"))
        assertTrue(file.length() > 20)
    }

    @Test
    fun duplicateServerAndUserIsRejected() = runBlocking {
        val first = store.add(newAccount())
        try {
            store.add(newAccount(server = "https://CLOUD.example.org"))
            fail()
        } catch (e: AccountAlreadyExistsException) {
            assertEquals(first, e.existing)
        }
        store.add(newAccount(user = "bob")) // other user on the same server is fine
        assertEquals(2, store.list().size)
    }

    @Test
    fun invalidatedKeyYieldsNullCredentialsButKeepsAccount() = runBlocking {
        val account = store.add(newAccount())
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() // e.g. restored on another device
        assertNull(store.credentials(account.id))
        assertNotNull(store.get(account.id))

        store.updateCredentials(account.id, Credentials("alice", "new-pw"))
        assertEquals("new-pw", store.credentials(account.id)!!.appPassword)
        assertEquals(account, store.get(account.id)) // data kept
    }

    @Test
    fun credentialsAreBoundToTheAccountId() = runBlocking {
        val a = store.add(newAccount("alice"))
        val b = store.add(newAccount("bob"))
        // swapping the encrypted files must not leak one account's password to the other
        val fa = java.io.File(credentialsDir, "${a.id}.enc")
        val fb = java.io.File(credentialsDir, "${b.id}.enc")
        val ta = fa.readText()
        fa.writeText(fb.readText())
        fb.writeText(ta)
        assertNull(store.credentials(a.id))
        assertNull(store.credentials(b.id))
    }

    @Test
    fun removeDeletesCredentialsRowsAndLocalFiles() = runBlocking {
        val account = store.add(newAccount())
        db.bookDao().upsertAll(listOf(book(account.id)))
        val dir = java.io.File(booksRoot, account.id).also { it.mkdirs() }
        java.io.File(dir, "1.epub").writeText("data")
        val other = store.add(newAccount("bob"))
        val otherDir = java.io.File(booksRoot, other.id).also { it.mkdirs() }
        java.io.File(otherDir, "1.epub").writeText("keep")

        store.remove(account.id)

        assertEquals(listOf(account.id), cancelled)
        assertNull(store.get(account.id))
        assertNull(store.credentials(account.id))
        assertNull(db.bookDao().get(account.id, 1))
        assertFalse(dir.exists())
        assertTrue("other account untouched", java.io.File(otherDir, "1.epub").exists())
        assertNotEquals(0, credentialsDir.listFiles()!!.size)
    }

    private fun book(accountId: String) = BookEntity(
        accountId, 1, "epub", "/a.epub", 1, "A", "[]", null, null, null, null, null, null, null, null, "unread",
        false, null, null, 1, 1, 1, true, true, "[]", false,
    )
}
