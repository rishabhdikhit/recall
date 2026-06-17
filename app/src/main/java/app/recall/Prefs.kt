package app.recall

import android.content.Context

object Prefs {
    private const val FILE = "recall_prefs"
    private const val KEY_GEMINI = "gemini_key"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun geminiKey(ctx: Context): String = sp(ctx).getString(KEY_GEMINI, "") ?: ""

    fun setGeminiKey(ctx: Context, value: String) {
        sp(ctx).edit().putString(KEY_GEMINI, value.trim()).apply()
    }
}
