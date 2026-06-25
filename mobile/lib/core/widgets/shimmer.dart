/// A lightweight, dependency-free shimmer (skeleton) loading effect.
///
/// WHY hand-rolled (not the `shimmer` package): every dependency grows the APK a
/// citizen on a tiny bundle must download (PRD §15 data-budget). A shimmer is a
/// single animated linear gradient swept across a [ShaderMask], so we build it
/// from the SDK and keep the APK lean. Skeleton placeholders make slow 2G/3G
/// loads feel responsive and intentional (the content's shape is hinted before
/// it arrives) instead of showing a bare spinner.
library;

import 'package:flutter/material.dart';

import '../theme/app_palette.dart';

/// Wraps [child] in a sweeping shimmer gradient while content loads.
///
/// Drive it over a column of [ShimmerBox]es shaped like the real content. The
/// animation auto-disposes with the widget; it draws nothing expensive (one
/// gradient repaint) so it is cheap even on low-end GPUs.
class Shimmer extends StatefulWidget {
  /// Wraps [child] in the shimmer effect.
  const Shimmer({required this.child, super.key});

  /// The skeleton layout to shimmer over (typically [ShimmerBox]es).
  final Widget child;

  @override
  State<Shimmer> createState() => _ShimmerState();
}

class _ShimmerState extends State<Shimmer>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1300),
  )..repeat();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final base = scheme.surfaceContainerHighest;
    final highlight = scheme.surfaceContainerHigh;
    return AnimatedBuilder(
      animation: _controller,
      child: widget.child,
      builder: (context, child) {
        final t = _controller.value;
        return ShaderMask(
          blendMode: BlendMode.srcATop,
          shaderCallback: (bounds) => LinearGradient(
            begin: Alignment(-1.0 - 2 * t, 0),
            end: Alignment(1.0 - 2 * t, 0),
            colors: [base, highlight, base],
            stops: const [0.3, 0.5, 0.7],
          ).createShader(bounds),
          child: child,
        );
      },
    );
  }
}

/// A single rounded placeholder block for a [Shimmer] skeleton.
class ShimmerBox extends StatelessWidget {
  /// Creates a placeholder of [width] x [height] with optional [radius].
  const ShimmerBox({
    this.width,
    required this.height,
    this.radius = 8,
    super.key,
  });

  /// Block width, or `null` to fill the parent.
  final double? width;

  /// Block height.
  final double height;

  /// Corner radius.
  final double radius;

  @override
  Widget build(BuildContext context) => Container(
    width: width,
    height: height,
    decoration: BoxDecoration(
      // The actual colour is irrelevant — the ShaderMask paints over it; we use
      // a solid surface so the mask has full coverage to sweep across.
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(radius),
    ),
  );
}

/// A ready-made skeleton that mirrors the shape of a feed card, shown while the
/// feed loads so the screen feels alive on slow networks.
class FeedCardSkeleton extends StatelessWidget {
  /// Creates the feed-card skeleton.
  const FeedCardSkeleton({super.key});

  @override
  Widget build(BuildContext context) => Shimmer(
    child: Container(
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(AppPalette.radiusCard),
      ),
      padding: const EdgeInsets.all(AppPalette.spaceLg),
      child: const Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              ShimmerBox(width: 40, height: 40, radius: 20),
              SizedBox(width: AppPalette.spaceMd),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    ShimmerBox(width: 120, height: 12),
                    SizedBox(height: AppPalette.spaceSm),
                    ShimmerBox(width: 80, height: 10),
                  ],
                ),
              ),
            ],
          ),
          SizedBox(height: AppPalette.spaceLg),
          ShimmerBox(height: 14),
          SizedBox(height: AppPalette.spaceSm),
          ShimmerBox(height: 14),
          SizedBox(height: AppPalette.spaceSm),
          ShimmerBox(width: 200, height: 14),
        ],
      ),
    ),
  );
}
