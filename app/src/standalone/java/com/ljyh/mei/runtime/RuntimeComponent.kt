package com.ljyh.mei.runtime

import com.ljyh.mei.standalone.StandaloneAccountController
import com.ljyh.mei.standalone.StandaloneSessionStore

interface RuntimeComponent {
    fun standaloneSessions(): StandaloneSessionStore
    fun standaloneAccounts(): StandaloneAccountController
}
