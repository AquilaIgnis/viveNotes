package com.vivenotes.ui.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccountScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun hostedAccountActionsAreAvailable() {
        var login = false
        var signUp = false
        compose.setContent {
            ViveNotesTheme {
                AccountScreen(
                    onBack = {},
                    onLogIn = { login = true },
                    onSignUp = { signUp = true },
                )
            }
        }

        compose.onNodeWithTag(AccountTags.LOGIN).performClick()
        compose.onNodeWithTag(AccountTags.SIGN_UP).performClick()

        assertTrue(login)
        assertTrue(signUp)
    }

    @Test
    fun selfHostRevealsUrlEmailAndPasswordFields() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SERVER_URL).assertDoesNotExist()

        compose.onNodeWithTag(AccountTags.SELF_HOST).performClick()

        compose.onNodeWithTag(AccountTags.SERVER_URL).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.EMAIL).assertIsDisplayed()
        compose.onNodeWithTag(AccountTags.PASSWORD).assertIsDisplayed()
    }

    @Test
    fun selfHostAcceptsConnectionDetails() {
        setScreen()
        compose.onNodeWithTag(AccountTags.SELF_HOST).performClick()

        compose.onNodeWithTag(AccountTags.SERVER_URL)
            .performTextInput("https://notes.example.com")
        compose.onNodeWithTag(AccountTags.EMAIL)
            .performTextInput("writer@example.com")
        compose.onNodeWithTag(AccountTags.PASSWORD)
            .performTextInput("correct horse battery staple")

        compose.onNodeWithTag(AccountTags.SERVER_URL)
            .assertTextContains("https://notes.example.com")
        compose.onNodeWithTag(AccountTags.EMAIL)
            .assertTextContains("writer@example.com")
    }

    private fun setScreen() {
        compose.setContent {
            ViveNotesTheme {
                AccountScreen(onBack = {})
            }
        }
    }
}
