package net.vaydns.phoenix

import android.graphics.drawable.Drawable

data class AppListItem(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    var isSelected: Boolean = false // Adding this helps if you need checkboxes!
)