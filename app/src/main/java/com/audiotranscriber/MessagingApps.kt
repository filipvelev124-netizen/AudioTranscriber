package com.audiotranscriber

data class MessagingApp(val packageName: String, val displayName: String)

val MESSAGING_APPS = listOf(
    MessagingApp("com.whatsapp",                "WhatsApp"),
    MessagingApp("com.whatsapp.w4b",            "WhatsApp Business"),
    MessagingApp("org.telegram.messenger",      "Telegram"),
    MessagingApp("org.telegram.plus",           "Telegram X"),
    MessagingApp("com.instagram.android",       "Instagram"),
    MessagingApp("com.facebook.orca",           "Messenger"),
    MessagingApp("com.viber.voip",              "Viber"),
    MessagingApp("com.discord",                 "Discord"),
    MessagingApp("com.snapchat.android",        "Snapchat"),
    MessagingApp("org.thoughtcrime.securesms",  "Signal"),
    MessagingApp("im.vector.app",               "Element"),
    MessagingApp("com.skype.raider",            "Skype"),
    MessagingApp("com.microsoft.teams",         "Microsoft Teams"),
)
