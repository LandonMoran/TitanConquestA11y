package com.titanconquest.a11y.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.titanconquest.a11y.accessibility.A11yLabels

@Composable
fun LoginScreen(
    isLoading: Boolean,
    errorMessage: String?,
    onLogin: (username: String, password: String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Titan Conquest",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics {
                contentDescription = "Titan Conquest — Accessible Client"
            }
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Accessible client for blind and low-vision players",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(40.dp))

        // Username field
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(A11yLabels.FIELD_USERNAME) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Username field. Enter your Titan Conquest username." },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            enabled = !isLoading
        )

        Spacer(Modifier.height(16.dp))

        // Password field
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(A11yLabels.FIELD_PASSWORD) },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Password field. Enter your password." },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    if (username.isNotBlank() && password.isNotBlank()) {
                        onLogin(username, password)
                    }
                }
            ),
            trailingIcon = {
                TextButton(
                    onClick = { showPassword = !showPassword },
                    modifier = Modifier.semantics {
                        contentDescription = if (showPassword) "Hide password" else "Show password"
                    }
                ) {
                    Text(if (showPassword) "Hide" else "Show")
                }
            },
            enabled = !isLoading
        )

        // Error message — announced automatically by TalkBack as a live region
        if (errorMessage != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics {
                    contentDescription = "Error: $errorMessage"
                }
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onLogin(username, password) },
            enabled = !isLoading && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp) // Large touch target for motor accessibility
                .semantics { contentDescription = A11yLabels.BUTTON_LOGIN }
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Log In", style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Link to register / forgot password
        TextButton(
            onClick = { /* open browser to titanconquest.com/register.php */ },
            modifier = Modifier.semantics {
                contentDescription = "Register a new account on titanconquest.com"
            }
        ) {
            Text("Don't have an account? Register")
        }

        TextButton(
            onClick = { /* open browser to titanconquest.com/forgot.php */ },
            modifier = Modifier.semantics {
                contentDescription = "Forgot your password — open reset page"
            }
        ) {
            Text("Forgot password?")
        }
    }
}
