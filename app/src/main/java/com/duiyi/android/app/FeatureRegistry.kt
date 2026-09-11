package com.duiyi.android.app

import com.duiyi.android.domain.Copy
import com.duiyi.android.domain.FeatureDescriptor
import com.duiyi.android.domain.FeatureID

object FeatureRegistry {
    val modules: List<FeatureDescriptor> = listOf(
        FeatureDescriptor(
            id = FeatureID.FaceToFace,
            title = { Copy.modeFaceToFace(it) },
            subtitle = { Copy.homeFaceToFaceSubtitle(it) },
        ),
        FeatureDescriptor(
            id = FeatureID.Simultaneous,
            title = { Copy.modeSimultaneous(it) },
            subtitle = { Copy.homeSimultaneousSubtitle(it) },
        ),
        FeatureDescriptor(
            id = FeatureID.Meeting,
            title = { Copy.modeMeeting(it) },
            subtitle = { Copy.homeMeetingSubtitle(it) },
        ),
    )

    val visible: List<FeatureDescriptor> get() = modules
}
