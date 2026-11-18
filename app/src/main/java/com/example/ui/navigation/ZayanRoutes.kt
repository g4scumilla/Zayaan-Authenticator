package com.example.ui.navigation

import kotlinx.serialization.Serializable

sealed interface ZayanRoute {
    @Serializable
    data object Splash : ZayanRoute

    @Serializable
    data object Welcome : ZayanRoute

    @Serializable
    data object GoogleSignIn : ZayanRoute

    @Serializable
    data object RecoverySetup : ZayanRoute

    @Serializable
    data object RecoveryUnlock : ZayanRoute

    @Serializable
    data object Dashboard : ZayanRoute

    @Serializable
    data object AddAccount : ZayanRoute

    @Serializable
    data object QrScanner : ZayanRoute

    @Serializable
    data class ManualEntry(val prefillIssuer: String = "") : ZayanRoute

    @Serializable
    data class AccountDetail(val accountId: String) : ZayanRoute

    @Serializable
    data object BackupSync : ZayanRoute

    @Serializable
    data object SecuritySettings : ZayanRoute

    @Serializable
    data object AppSettings : ZayanRoute

    @Serializable
    data object About : ZayanRoute
}
