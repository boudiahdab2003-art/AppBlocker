package com.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.R
import com.appblocker.data.AiCoach
import com.appblocker.data.ChatMode
import com.appblocker.data.ChatMsg
import com.appblocker.data.CoachActions
import com.appblocker.data.CoachError
import com.appblocker.data.CoachOutcome
import com.appblocker.data.CoachProfile
import com.appblocker.data.DisplayName
import com.appblocker.data.Goal
import com.appblocker.data.Goals
import com.appblocker.data.SettingsStore
import com.appblocker.data.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Drives the AI Coach chat: persisted history, one Gemini call per sent message, the goal list
 *  the coach maintains from the conversation, the 🆘 urge button and the coach's action buttons. */
class CoachChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val messages: StateFlow<List<ChatMsg>> = _messages

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    /** When the current request started (uptime-free wall millis), for the "Thinking… 12 s"
     *  counter — a deep answer can take a while, and a number that moves says it is coming. */
    private val _sendingSince = MutableStateFlow(0L)
    val sendingSince: StateFlow<Long> = _sendingSince

    private val _goals = MutableStateFlow<List<Goal>>(emptyList())
    val goals: StateFlow<List<Goal>> = _goals

    // Tappable prompts above the input: static starters on open, then whatever follow-ups
    // the coach suggests with each reply.
    private val _suggestions = MutableStateFlow(defaultSuggestions())
    val suggestions: StateFlow<List<String>> = _suggestions

    // Everything the coach has learned about the user, for the "What your coach knows" dialog.
    private val _profile = MutableStateFlow<Map<String, String>>(emptyMap())
    val profile: StateFlow<Map<String, String>> = _profile

    /** Action buttons already tapped ([CoachActions.key]) — they stay ✅. */
    private val _applied = MutableStateFlow<Set<String>>(emptySet())
    val applied: StateFlow<Set<String>> = _applied

    /** What the block screen had just blocked, while that moment is still the context. */
    private var blockedFrom: String? = null
    private var blockedAt = 0L

    init {
        val ctx = getApplication<Application>()
        val history = AiCoach.chatHistory(ctx)
        _messages.value = history.ifEmpty { listOf(greeting()) }
        _goals.value = Goals.all(ctx)
        _profile.value = CoachProfile.all(ctx)
        _applied.value = CoachActions.applied(ctx)
    }

    private val words get() = Words.of(getApplication())

    private fun greeting(): ChatMsg {
        // The name can be unset — someone who skipped it during setup would otherwise be met with
        // "Hi !" as the very first thing the coach ever says to them.
        val stored = SettingsStore.userName(getApplication())
        val hello = if (DisplayName.isSet(stored)) "Hi ${DisplayName.first(stored)}!" else "Hi!"
        return ChatMsg("local",
            "$hello I'm your coach — I can see your screen time and what's set up in the " +
                "app. Ask me anything, or let's set a goal together. The more we talk, the " +
                "better I get to know you.")
    }

    fun send(text: String) = send(text, ChatMode.NORMAL, kind = null)

    private fun send(text: String, mode: ChatMode, kind: String?) {
        val msg = text.trim()
        if (msg.isEmpty() || _sending.value) return
        val ctx = getApplication<Application>()
        _messages.value = _messages.value + ChatMsg("user", msg, at = System.currentTimeMillis(), kind = kind)
        AiCoach.saveChat(ctx, _messages.value)
        request(msg, mode)
    }

    /** One request for [msg], which is already the last user message on screen. */
    private fun request(msg: String, mode: ChatMode) {
        val ctx = getApplication<Application>()
        _sending.value = true
        _sendingSince.value = System.currentTimeMillis()
        _suggestions.value = emptyList()
        val from = blockedFrom?.takeIf { System.currentTimeMillis() - blockedAt < BLOCK_CONTEXT_MS }
        viewModelScope.launch {
            val history = _messages.value
                .filter { it.role == "user" || it.role == "model" }
                .dropLast(1) // chat() re-appends the new message itself
            when (val outcome = AiCoach.chat(ctx, history, msg, mode, from)) {
                is CoachOutcome.Ok -> {
                    _messages.value = _messages.value + ChatMsg(
                        "model", outcome.reply.reply, outcome.reply.actions,
                        at = System.currentTimeMillis(),
                    )
                    AiCoach.saveChat(ctx, _messages.value)
                    _goals.value = Goals.all(ctx)
                    _profile.value = CoachProfile.all(ctx)
                    _suggestions.value = outcome.reply.suggestions
                }
                // Named, not the old one-size-fits-all "Couldn't reach Gemini". Five different
                // problems used to read identically here, so the only report possible was "the
                // coach isn't working" — and the answer that day was "you haven't installed the
                // update", which this bubble would have said in one line.
                is CoachOutcome.Failed ->
                    _messages.value = _messages.value + ChatMsg(
                        "local", explain(outcome.error), at = System.currentTimeMillis(),
                        kind = ChatMsg.KIND_ERROR,
                    )
            }
            _sending.value = false
            _sendingSince.value = 0L
        }
    }

    /**
     * **"Try again"** under a failed reply: the same message once more, without typing it again
     * and without a second copy of it in the conversation. Only offered while the failure is the
     * newest thing on screen.
     */
    fun retry() {
        if (_sending.value) return
        val list = _messages.value
        if (list.lastOrNull()?.kind != ChatMsg.KIND_ERROR) return
        val lastUser = list.lastOrNull { it.role == "user" } ?: return
        _messages.value = list.dropLast(1)
        val mode = if (lastUser.kind == ChatMsg.KIND_URGE) ChatMode.URGE else ChatMode.NORMAL
        request(lastUser.text, mode)
    }

    /**
     * **🆘 "I have an urge"** (invariant 114, his pick). Two answers: a calm card that appears at
     * once — written into the app, so it works offline and before any model has thought — with a
     * Strict button on it; then the coach's own reply, in urge mode. The press is recorded (time
     * only) so the coach can notice when urges come.
     */
    fun urge() {
        if (_sending.value) return
        val ctx = getApplication<Application>()
        val now = System.currentTimeMillis()
        AiCoach.recordUrge(ctx, now)
        val text = words.get(R.string.coach_urge_message)
        _messages.value = _messages.value + ChatMsg("user", text, at = now, kind = ChatMsg.KIND_URGE)
        AiCoach.saveChat(ctx, _messages.value)
        _messages.value = _messages.value + ChatMsg(
            "local", words.get(R.string.coach_urge_card),
            actions = listOf(CoachActions.Action(CoachActions.Type.STRICT, minutes = URGE_STRICT_MINUTES)),
            at = now + 1, kind = ChatMsg.KIND_URGE,
        )
        request(text, ChatMode.URGE)
    }

    /**
     * Opened from the block screen's **"Talk to coach"**. Says what just happened in a local bubble
     * — nothing is sent until he writes or taps — and keeps [label] as context for the next few
     * minutes of conversation. [label] is an app's name or a plain category, never a word or site.
     */
    fun openedFromBlock(label: String?) {
        val now = System.currentTimeMillis()
        val what = label?.takeIf { it.isNotBlank() } ?: words.get(R.string.coach_blocked_something)
        blockedFrom = what
        blockedAt = now
        _messages.value = _messages.value + ChatMsg(
            "local", words.get(R.string.coach_blocked_intro, what), at = now,
            kind = ChatMsg.KIND_BLOCKED,
        )
        _suggestions.value = listOf(
            words.get(R.string.coach_blocked_why, what),
            words.get(R.string.coach_blocked_instead),
        )
    }

    /**
     * A tap on one of the coach's buttons (or the urge card's). Strengthen-only, no undo — the
     * same rule as the smart suggestions. Saved on a scope that outlives this screen, so leaving at
     * once still keeps the lock; the ✅ is taken back if saving fails.
     */
    fun applyAction(msg: ChatMsg, action: CoachActions.Action) {
        val ctx = getApplication<Application>()
        val key = CoachActions.key(msg.at, action)
        if (key in _applied.value) return
        _applied.value = _applied.value + key
        val label = action.appLabel ?: action.pkg.orEmpty()
        val scheduleName = words.get(R.string.suggest_limit_schedule_name, label)
        actionScope.launch {
            val ok = runCatching { CoachActions.apply(ctx, action, scheduleName, key) }.isSuccess
            if (!ok) withContext(Dispatchers.Main) { _applied.value = _applied.value - key }
        }
    }

    fun removeGoal(goal: Goal) {
        val ctx = getApplication<Application>()
        Goals.remove(ctx, goal.id)
        _goals.value = Goals.all(ctx)
    }

    fun clearChat() {
        AiCoach.clearChat(getApplication())
        _messages.value = listOf(greeting())
        _suggestions.value = defaultSuggestions()
    }

    /** The Gemini model that last answered, for the "what your coach knows" dialog. */
    fun coachModel(): String? = AiCoach.lastModelUsed(getApplication())

    /** The last failure and when, for the same dialog — the line to read back when the coach
     *  stops answering, so the reason arrives with the report instead of after it. */
    fun coachFailure(): Pair<CoachError, Long>? = AiCoach.lastError(getApplication())

    fun clearProfile() {
        CoachProfile.clear(getApplication())
        // "Forget what you know about me" has to include what the coach ADVISED, or it would keep
        // referring back to past advice while claiming to know nothing about the user. Deliberately
        // not cleared by clearChat: like the profile, this is memory rather than conversation.
        AiCoach.clearAdviceLog(getApplication())
        _profile.value = emptyMap()
    }

    /**
     * One sentence per cause, written for a non-technical reader: what happened, and whether
     * it is his to fix. No codes, no "Gemini" — the point is that he can act on it or tell me
     * which one it was without knowing anything about the inside of the app.
     */
    private fun explain(e: CoachError): String = when (e) {
        CoachError.OFFLINE ->
            "Your phone isn't online right now — the coach needs a connection. " +
                "Try again once you're back on Wi-Fi or data."
        CoachError.SERVER_DOWN ->
            "Couldn't reach the coach's server. It may be down or just slow — " +
                "try again in a minute."
        CoachError.QUOTA ->
            "The coach has used up today's free allowance from Google. It comes back " +
                "tomorrow — everything else in the app is unaffected."
        CoachError.REJECTED ->
            "The server turned us away. That's a key or password problem on my side, " +
                "not yours — send a report and I'll fix it."
        CoachError.BAD_REPLY ->
            "The coach answered in a form the app couldn't read. Try asking again; " +
                "if it keeps happening, send a report."
        CoachError.BLOCKED -> words.get(R.string.coach_error_blocked)
        CoachError.NO_KEY ->
            "The coach isn't set up in this build — there's no key for it to use."
        CoachError.UNKNOWN ->
            "Something went wrong that I haven't seen before. Please send a report — " +
                "it'll carry the details."
    }

    private fun defaultSuggestions(): List<String> = listOf(
        "Give me my weekly report",
        "Set a goal for this week with a plan",
        "How am I doing today?",
    )

    private companion object {
        /** The urge card's own button: an hour of Strict, the shortest that outlasts an urge. */
        const val URGE_STRICT_MINUTES = 60

        /** How long "you were just blocked from X" stays the context of the conversation. */
        const val BLOCK_CONTEXT_MS = 10 * 60_000L

        /** Taps are saved here, not on viewModelScope — see [applyAction]. */
        val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
