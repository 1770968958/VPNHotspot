package be.mygod.vpnhotspot.ui.apconfiguration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import be.mygod.vpnhotspot.R
import be.mygod.vpnhotspot.enterprise.EnterpriseApProfile
import be.mygod.vpnhotspot.enterprise.EnterpriseProfiles
import be.mygod.vpnhotspot.enterprise.EnterpriseSecurityMode
import be.mygod.vpnhotspot.enterprise.EnterpriseUser
import be.mygod.vpnhotspot.ui.DialogConfirmButton
import be.mygod.vpnhotspot.ui.DialogDismissButton
import be.mygod.vpnhotspot.ui.PreferenceRow
import java.util.UUID

@Composable
internal fun EnterpriseUsersRow(
    mode: EnterpriseSecurityMode,
    onProfileSaved: () -> Unit = {},
) {
    val initial = remember(mode) {
        runCatching { EnterpriseProfiles.load() }.getOrNull()?.let { stored ->
            stored.copy(mode = mode)
        } ?: EnterpriseApProfile(mode)
    }
    var profile by remember(mode) { mutableStateOf(initial) }
    var editing by remember { mutableStateOf(false) }
    val enabled = profile.enabledUsers.size
    PreferenceRow(
        icon = R.drawable.ic_devices,
        title = stringResource(R.string.enterprise_users_title),
        summary = stringResource(R.string.enterprise_users_summary, profile.users.size, enabled),
        onClick = { editing = true },
    )
    if (editing) EnterpriseUsersDialog(
        profile = profile,
        onDismiss = { editing = false },
        onSave = { updated ->
            EnterpriseProfiles.save(updated)
            profile = updated
            onProfileSaved()
            editing = false
        },
    )
}

@Composable
private fun EnterpriseUsersDialog(
    profile: EnterpriseApProfile,
    onDismiss: () -> Unit,
    onSave: (EnterpriseApProfile) -> Unit,
) {
    var users by remember(profile) { mutableStateOf(profile.users) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    val candidate = profile.copy(users = users)
    val validationError = runCatching { candidate.validate() }.exceptionOrNull()?.localizedMessage

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enterprise_users_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((index, user) in users.withIndex()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { editIndex = index },
                        ) {
                            Text(if (user.enabled) user.username else "${user.username} · off")
                        }
                        TextButton(onClick = { users = users.filterIndexed { i, _ -> i != index } }) {
                            Text(stringResource(R.string.enterprise_user_delete))
                        }
                    }
                }
                TextButton(onClick = { adding = true }) {
                    Text(stringResource(R.string.enterprise_user_add))
                }
                if (validationError != null) Text(stringResource(R.string.enterprise_users_invalid))
            }
        },
        confirmButton = {
            DialogConfirmButton(
                enabled = validationError == null,
                onClick = { onSave(candidate) },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            DialogDismissButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )

    val selected = editIndex?.let(users::getOrNull)
    if (selected != null || adding) EnterpriseUserEditDialog(
        initial = selected,
        onDismiss = {
            editIndex = null
            adding = false
        },
        onSave = { edited ->
            val index = editIndex
            users = if (index == null) users + edited else users.toMutableList().also { it[index] = edited }
            editIndex = null
            adding = false
        },
    )
}

@Composable
private fun EnterpriseUserEditDialog(
    initial: EnterpriseUser?,
    onDismiss: () -> Unit,
    onSave: (EnterpriseUser) -> Unit,
) {
    var username by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var enabled by remember(initial) { mutableStateOf(initial?.enabled ?: true) }
    val valid = username.isNotBlank() && password.isNotEmpty() &&
            username.none { it == '"' || it == '\\' || it == '\n' || it == '\r' || it == '\t' || it.code == 0 } &&
            password.none { it == '"' || it == '\\' || it == '\n' || it == '\r' || it == '\t' || it.code == 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enterprise_user_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.enterprise_user_username)) },
                    singleLine = true,
                    shape = OutlinedTextFieldDefaults.roundedShape,
                    colors = OutlinedTextFieldDefaults.tonalColors(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.enterprise_user_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = OutlinedTextFieldDefaults.roundedShape,
                    colors = OutlinedTextFieldDefaults.tonalColors(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.enterprise_user_enabled))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            DialogConfirmButton(
                enabled = valid,
                onClick = {
                    onSave(EnterpriseUser(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        username = username,
                        password = password,
                        enabled = enabled,
                    ))
                },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            DialogDismissButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
