package com.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.AiCoach
import com.appblocker.data.ChatMsg
import com.appblocker.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Drives the AI Coach chat: persisted history, one Gemini call per sent message, and the
 *  goal list the coach maintains from the conversation. */
class CoachChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val messages: StateFlow<List<ChatMsg>> = _messages

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    private val _goals = MutableStateFlow<List<String>>(emptyList())
    val goals: StateFlow<List<String>> = _goals

    // Tappable prompts above the input: static starters on open, then whatever follow-ups
    // the coach suggests with each reply.
    private val _suggestions = MutableStateFlow(DEFAULT_SUGGESTIONS)
    val suggestions: StateFlow<List<String>> = _suggestions

    init {
        val ctx = getApplication<Application>()
        val history = AiCoach.chatHistory(ctx)
        _messages.value = history.ifEmpty { listOf(greeting()) }
        _goals.value = AiCoach.goals(ctx)
    }

    private fun greeting(): ChatMsg {
        val first = SettingsStore.userName(getApplication()).substringBefore(' ')
        return ChatMsg("local",
            "Hi $first! I'm your coach — I can see your screen time and what's set up in the " +
                "app. Ask me anything, or let's set a long-term goal together.")
    }

    fun send(text: String) {
        val msg = text.trim()
        if (msg.isEmpty() || _sending.value) return
        val ctx = getApplication<Application>()
        // Drop any stale local error bubble; the greeting can stay as conversation opener.
        _messages.value = _messages.value + ChatMsg("user", msg)
        AiCoach.saveChat(ctx, _messages.value)
        _sending.value = true
        _suggestions.value = emptyList()
        viewModelScope.launch {
            val history = _messages.value
                .filter { it.role == "user" || it.role == "model" }
                .dropLast(1) // chat() re-appends the new message itself
            val reply = AiCoach.chat(ctx, history, msg)
            if (reply != null) {
                _messages.value = _messages.value + ChatMsg("model", reply.reply)
                AiCoach.saveChat(ctx, _messages.value)
                _goals.value = AiCoach.goals(ctx)
                _suggestions.value = reply.suggestions
            } else {
                _messages.value = _messages.value +
                    ChatMsg("local", "Couldn't reach Gemini — check your connection and try again.")
            }
            _sending.value = false
        }
    }

    fun removeGoal(goal: String) {
        val ctx = getApplication<Application>()
        val updated = _goals.value - goal
        AiCoach.setGoals(ctx, updated)
        _goals.value = updated
    }

    fun clearChat() {
        AiCoach.clearChat(getApplication())
        _messages.value = listOf(greeting())
        _suggestions.value = DEFAULT_SUGGESTIONS
    }

    private companion object {
        val DEFAULT_SUGGESTIONS = listOf(
            "Give me my weekly report",
            "Set a goal for this week with a plan",
            "How am I doing today?",
        )
    }
}
