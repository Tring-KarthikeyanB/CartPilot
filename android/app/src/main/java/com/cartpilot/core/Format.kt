package com.cartpilot.core

import java.util.Locale

fun formatUsd(amount: Double): String = String.format(Locale.US, "$%,.2f", amount)

fun formatCount(n: Int): String = String.format(Locale.US, "%,d", n)
