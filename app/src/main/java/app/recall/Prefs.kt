package app.recall

import android.content.Context

object Prefs {
    private const val FILE = "recall_prefs"
    private const val KEY_GEMINI = "gemini_key"
    private const val KEY_MODEL = "gemini_model"
    private const val KEY_ONBOARDED = "onboarding_done"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun geminiKey(ctx: Context): String = sp(ctx).getString(KEY_GEMINI, "") ?: ""

    fun setGeminiKey(ctx: Context, value: String) {
        sp(ctx).edit().putString(KEY_GEMINI, value.trim()).apply()
    }

    fun geminiModel(ctx: Context): String =
        sp(ctx).getString(KEY_MODEL, Gemini.DEFAULT_MODEL) ?: Gemini.DEFAULT_MODEL

    fun setGeminiModel(ctx: Context, value: String) {
        sp(ctx).edit().putString(KEY_MODEL, value).apply()
    }

    fun onboardingDone(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ONBOARDED, false)

    fun setOnboardingDone(ctx: Context) {
        sp(ctx).edit().putBoolean(KEY_ONBOARDED, true).apply()
    }
}
