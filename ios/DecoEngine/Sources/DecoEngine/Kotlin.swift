import Foundation

// Small helpers that reproduce Kotlin semantics exactly, so results match the Android engine.

/// Kotlin `Double.roundToInt()`: nearest integer, ties toward +infinity (Math.round).
@inlinable public func kRound(_ x: Double) -> Int {
    Int((x + 0.5).rounded(.down))
}

extension Comparable {
    @inlinable func coerceIn(_ lo: Self, _ hi: Self) -> Self { Swift.min(Swift.max(self, lo), hi) }
    @inlinable func coerceAtLeast(_ lo: Self) -> Self { Swift.max(self, lo) }
    @inlinable func coerceAtMost(_ hi: Self) -> Self { Swift.min(self, hi) }
}

/// Kotlin `"%.Nf".format(x)` (always uses '.' as decimal separator).
@inlinable public func fmt(_ x: Double, _ decimals: Int) -> String {
    String(format: "%.\(decimals)f", locale: Locale(identifier: "en_US_POSIX"), x)
}
