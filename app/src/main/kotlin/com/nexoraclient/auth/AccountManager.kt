package com.rubidiumclient.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.accountDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "ox_accounts")

object AccountManager {

    private const val TAG = "AccountManager"

    private val KEY_ACCOUNTS         = stringPreferencesKey("accounts_json")
    private val KEY_SELECTED_ACCOUNT = stringPreferencesKey("selected_account_gamertag")

    private var dataStore: DataStore<Preferences>? = null
    private val gson  = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var _accounts: MutableList<SavedAccount> = mutableListOf()
    @Volatile private var _selectedGamertag: String? = null

    val accounts: List<SavedAccount> get() = _accounts.toList()

    val selectedAccount: SavedAccount?
        get() = _selectedGamertag?.let { tag -> _accounts.find { it.gamertag == tag } }

    private val _accountsFlow = MutableStateFlow<List<SavedAccount>>(emptyList())
    val accountsFlow: StateFlow<List<SavedAccount>> = _accountsFlow

    private val _selectedGamertagFlow = MutableStateFlow<String?>(null)
    val selectedGamertagFlow: StateFlow<String?> = _selectedGamertagFlow

    private fun publishState() {
        _accountsFlow.value = _accounts.toList()
        _selectedGamertagFlow.value = _selectedGamertag
    }

    fun init(context: Context) {
        if (dataStore != null) return
        dataStore = context.accountDataStore

        runBlocking {
            try {
                val prefs = withContext(Dispatchers.IO) { dataStore!!.data.first() }

                val accountsJson = prefs[KEY_ACCOUNTS]
                if (!accountsJson.isNullOrBlank()) {
                    val type = object : TypeToken<List<SavedAccount>>() {}.type
                    val loaded = gson.fromJson<List<SavedAccount>>(accountsJson, type)
                    _accounts = loaded.toMutableList()
                }

                _selectedGamertag = prefs[KEY_SELECTED_ACCOUNT]

            } catch (e: Exception) {
            }
        }
        publishState()
    }

    fun addAccount(account: SavedAccount) {

        val idx = _accounts.indexOfFirst { it.gamertag == account.gamertag }
        if (idx >= 0) _accounts[idx] = account else _accounts.add(account)
        persist()
        publishState()
    }

    fun removeAccount(account: SavedAccount) {
        _accounts.removeAll { it.gamertag == account.gamertag }
        if (_selectedGamertag == account.gamertag) clearSelectedAccount()
        persist()
        publishState()
    }

    fun selectAccount(account: SavedAccount) {
        _selectedGamertag = account.gamertag
        scope.launch {
            dataStore?.edit { it[KEY_SELECTED_ACCOUNT] = account.gamertag }
        }
        publishState()
    }

    fun clearSelectedAccount() {
        _selectedGamertag = null
        scope.launch {
            dataStore?.edit { it.remove(KEY_SELECTED_ACCOUNT) }
        }
        publishState()
    }

    fun refreshAccount(gamertag: String, newMcToken: String, newExpireMs: Long, newPrivateKeyB64: String? = null, newPublicKeyB64: String? = null) {
        val idx = _accounts.indexOfFirst { it.gamertag == gamertag }
        if (idx < 0) {
            return
        }
        _accounts[idx] = _accounts[idx].copy(
            mcToken      = newMcToken,
            expireTimeMs = newExpireMs,
            privateKeyB64 = newPrivateKeyB64 ?: _accounts[idx].privateKeyB64,
            publicKeyB64  = newPublicKeyB64 ?: _accounts[idx].publicKeyB64
        )
        persist()
        publishState()
    }

    fun getRelayReadyAccount(): SavedAccount? =
        selectedAccount?.takeIf { it.isRelayReady() }

    fun getActiveChain(): String? =
        selectedAccount?.mcToken?.takeIf { it.isNotBlank() }

    private fun persist() {
        scope.launch {
            try {
                val json = gson.toJson(_accounts)
                dataStore?.edit { prefs ->
                    prefs[KEY_ACCOUNTS] = json
                    _selectedGamertag?.let { prefs[KEY_SELECTED_ACCOUNT] = it }
                        ?: prefs.remove(KEY_SELECTED_ACCOUNT)
                }
            } catch (e: Exception) {
            }
        }
    }
}
