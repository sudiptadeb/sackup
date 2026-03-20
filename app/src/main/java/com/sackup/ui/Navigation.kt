package com.sackup.ui

object Routes {
    const val HOME = "home"
    const val SETUP = "setup/{groupId}"
    const val SETUP_NEW = "setup/new"
    const val PROGRESS = "progress"
    const val LOGS = "logs"
    const val CLEAR_SPACE = "clearspace/{groupId}"
    const val ANALYZE = "analyze/{groupId}"

    fun setup(groupId: Long) = "setup/$groupId"
    fun clearSpace(groupId: Long) = "clearspace/$groupId"
    fun analyze(groupId: Long) = "analyze/$groupId"
}
