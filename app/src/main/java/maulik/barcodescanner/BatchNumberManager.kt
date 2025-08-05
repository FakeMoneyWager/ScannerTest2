package maulik.barcodescanner

import android.content.Context
import android.content.SharedPreferences

object BatchNumberManager {

    private const val PREFS_NAME = "BatchPrefs"
    private const val KEY_BATCH_NUMBER = "current_batch_number"
    private const val INITIAL_BATCH_NUMBER = 1 // The numeric part of the initial ID

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Retrieves the current batch number, formats it, and returns it as a String.
     * e.g., "BX00001"
     */
    fun getCurrentBatchId(context: Context): String {
        val prefs = getPrefs(context)
        val currentNumber = prefs.getInt(KEY_BATCH_NUMBER, INITIAL_BATCH_NUMBER)
        // Formats the integer into a 6-digit string with leading zeros (e.g., 1 -> "000001")
        return "BX${String.format("%05d", currentNumber)}"
    }

    /**
     * Increments the stored batch number by one.
     * This should be called after a batch upload is successfully completed.
     */
    fun incrementBatchNumber(context: Context) {
        val prefs = getPrefs(context)
        val currentNumber = prefs.getInt(KEY_BATCH_NUMBER, INITIAL_BATCH_NUMBER)
        prefs.edit().putInt(KEY_BATCH_NUMBER, currentNumber + 1).apply()
    }

    /**
     * A utility function to reset the batch number, useful for testing.
     */
    @Suppress("unused")
    fun resetBatchNumber(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit().putInt(KEY_BATCH_NUMBER, INITIAL_BATCH_NUMBER).apply()
    }
}