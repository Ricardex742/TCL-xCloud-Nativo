package com.xcloud.tclnative.webrtc

/** Rank only profiles advertised by the decoder; never rewrite a profile-level-id. */
object CodecPreference {
    fun rank(profile: String?, lightweight: Boolean): Int = when(profile?.take(2)?.lowercase()) {
        "42" -> if(lightweight) 0 else 2 // Baseline/constrained baseline: no CABAC or B frames.
        "4d" -> 1
        "64" -> if(lightweight) 2 else 0
        else -> 3
    }
}
