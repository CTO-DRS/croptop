package app.croptop.core.engine

/**
 * Allocator-aware entry points for creating document-sized A16 selection masks. The input handle
 * supplies dimensions, tile pool, and the document memory-budget manager; returned handles retain
 * those allocation services and are released through the owning engine's TileStore destroy call.
 */
internal object NativeSelectionBudgetBridge {
    external fun nativeLastError(): String
    external fun destroyStore(store: Long)

    /** Creates an empty A16 selection with the dimensions and allocator of [allocationStore]. */
    external fun emptyA16MaskLike(allocationStore: Long): Long

    /** Rasterizes a rectangle into an A16 mask with the allocator of [allocationStore]. */
    external fun rasterizeRectLike(
        allocationStore: Long,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        antialias: Boolean,
    ): Long

    /** Rasterizes an ellipse into an A16 mask with the allocator of [allocationStore]. */
    external fun rasterizeEllipseLike(
        allocationStore: Long,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        antialias: Boolean,
    ): Long

    /** `xy` packs at most 1,000,000 x,y pairs; output shares [allocationStore]'s allocator. */
    external fun rasterizePolygonLike(allocationStore: Long, xy: DoubleArray, antialias: Boolean): Long

    /** Combines masks and writes the output into the document allocator. Operation: Replace=0, Add=1, Subtract=2, Intersect=3. */
    external fun combineLike(allocationStore: Long, selection: Long, shape: Long, operation: Int): Long

    /** Inverts an A16 selection into the document allocator. */
    external fun invertLike(allocationStore: Long, selection: Long): Long

    /** Applies Feather/Expand/Contract/Border/Smooth (0..4) into the document allocator. */
    external fun modifyLike(allocationStore: Long, selection: Long, operation: Int, radius: Float): Long
}
