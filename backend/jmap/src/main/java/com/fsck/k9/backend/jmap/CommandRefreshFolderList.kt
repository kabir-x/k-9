package com.fsck.k9.backend.jmap

import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.MessagingException
import java.lang.Exception
import rs.ltt.jmap.client.JmapClient
import rs.ltt.jmap.client.api.ErrorResponseException
import rs.ltt.jmap.client.api.InvalidSessionResourceException
import rs.ltt.jmap.client.api.MethodErrorResponseException
import rs.ltt.jmap.client.api.UnauthorizedException
import rs.ltt.jmap.common.Request.Invocation.ResultReference
import rs.ltt.jmap.common.entity.Mailbox
import rs.ltt.jmap.common.entity.Role
import rs.ltt.jmap.common.method.call.mailbox.ChangesMailboxMethodCall
import rs.ltt.jmap.common.method.call.mailbox.GetMailboxMethodCall
import rs.ltt.jmap.common.method.response.mailbox.ChangesMailboxMethodResponse
import rs.ltt.jmap.common.method.response.mailbox.GetMailboxMethodResponse

internal class CommandRefreshFolderList(
    private val backendStorage: BackendStorage,
    private val jmapClient: JmapClient,
    private val accountId: String
) {
    fun refreshFolderList() {
        try {
            val state = backendStorage.getExtraString(STATE)
            if (state == null) {
                fetchMailboxes()
            } else {
                fetchMailboxUpdates(state)
            }
        } catch (e: UnauthorizedException) {
            throw AuthenticationFailedException("Authentication failed", e)
        } catch (e: InvalidSessionResourceException) {
            throw MessagingException(e.message, true, e)
        } catch (e: ErrorResponseException) {
            throw MessagingException(e.message, true, e)
        } catch (e: MethodErrorResponseException) {
            throw MessagingException(e.message, e.isPermanentError, e)
        } catch (e: Exception) {
            throw MessagingException(e)
        }
    }

    private fun fetchMailboxes() {
        val call = jmapClient.call(GetMailboxMethodCall(accountId))
        val response = call.getMainResponseBlocking<GetMailboxMethodResponse>()
        val foldersOnServer = response.list

        val oldFolderServerIds = backendStorage.getFolderServerIds()
        val (foldersToUpdate, foldersToCreate) = foldersOnServer.partition { it.id in oldFolderServerIds }

        for (folder in foldersToUpdate) {
            backendStorage.changeFolder(folder.id, folder.name, folder.type)
        }

        val newFolders = foldersToCreate.map { folder ->
            FolderInfo(folder.id, folder.name, folder.type)
        }
        backendStorage.createFolders(newFolders)

        val newFolderServerIds = foldersOnServer.map { it.id }
        val removedFolderServerIds = oldFolderServerIds - newFolderServerIds
        backendStorage.deleteFolders(removedFolderServerIds)

        backendStorage.setExtraString(STATE, response.state)
    }

    private fun fetchMailboxUpdates(state: String) {
        try {
            fetchAllMailboxChanges(state)
        } catch (e: MethodErrorResponseException) {
            if (e.methodErrorResponse.type == ERROR_CANNOT_CALCULATE_CHANGES) {
                fetchMailboxes()
            } else {
                throw e
            }
        }
    }

    private fun fetchAllMailboxChanges(state: String) {
        var currentState = state
        do {
            val (newState, hasMoreChanges) = fetchMailboxChanges(currentState)
            currentState = newState
        } while (hasMoreChanges)
    }

    private fun fetchMailboxChanges(state: String): UpdateState {
        val multiCall = jmapClient.newMultiCall()
        val mailboxChangesCall = multiCall.call(ChangesMailboxMethodCall(accountId, state))
        val createdMailboxesCall = multiCall.call(
            GetMailboxMethodCall(
                accountId,
                mailboxChangesCall.createResultReference(ResultReference.Path.CREATED)
            )
        )
        val changedMailboxesCall = multiCall.call(
            GetMailboxMethodCall(
                accountId,
                mailboxChangesCall.createResultReference(ResultReference.Path.UPDATED),
                mailboxChangesCall.createResultReference(ResultReference.Path.UPDATED_PROPERTIES)
            )
        )
        multiCall.execute()

        val mailboxChangesResponse = mailboxChangesCall.getMainResponseBlocking<ChangesMailboxMethodResponse>()
        val createdMailboxResponse = createdMailboxesCall.getMainResponseBlocking<GetMailboxMethodResponse>()
        val changedMailboxResponse = changedMailboxesCall.getMainResponseBlocking<GetMailboxMethodResponse>()

        val foldersToCreate = createdMailboxResponse.list.map { folder ->
            FolderInfo(folder.id, folder.name, folder.type)
        }
        backendStorage.createFolders(foldersToCreate)

        for (folder in changedMailboxResponse.list) {
            backendStorage.changeFolder(folder.id, folder.name, folder.type)
        }

        val destroyed = mailboxChangesResponse.destroyed
        destroyed?.let {
            backendStorage.deleteFolders(it.toList())
        }

        backendStorage.setExtraString(STATE, mailboxChangesResponse.newState)

        return UpdateState(
            state = mailboxChangesResponse.newState,
            hasMoreChanges = mailboxChangesResponse.isHasMoreChanges
        )
    }

    private val Mailbox.type: FolderType
        get() = when (role) {
            Role.INBOX -> FolderType.INBOX
            Role.ARCHIVE -> FolderType.ARCHIVE
            Role.DRAFTS -> FolderType.DRAFTS
            Role.SENT -> FolderType.SENT
            Role.TRASH -> FolderType.TRASH
            Role.JUNK -> FolderType.SPAM
            else -> FolderType.REGULAR
        }

    private val MethodErrorResponseException.isPermanentError: Boolean
        get() = methodErrorResponse.type != ERROR_SERVER_UNAVAILABLE

    companion object {
        private const val STATE = "jmapState"
        private const val ERROR_SERVER_UNAVAILABLE = "serverUnavailable"
        private const val ERROR_CANNOT_CALCULATE_CHANGES = "cannotCalculateChanges"
    }

    private data class UpdateState(val state: String, val hasMoreChanges: Boolean)
}
