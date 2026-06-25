/// Small, reusable state widgets for the load/empty/error states every data
/// screen must handle (CLAUDE.md: empty/error/offline states are first-class).
///
/// These are intentionally elegant — a soft, circular icon badge, generous
/// breathing room, and clear, friendly copy — so a slow load or a dropped 2G
/// link still feels like a considered part of the product, not a failure screen.
library;

import 'package:flutter/material.dart';

import '../theme/app_palette.dart';

/// A centred loading spinner with a localised label.
class LoadingView extends StatelessWidget {
  /// Creates a loading view with the given [label].
  const LoadingView({required this.label, super.key});

  /// The localised "loading" text.
  final String label;

  @override
  Widget build(BuildContext context) => Center(
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const CircularProgressIndicator(strokeWidth: 3),
        const SizedBox(height: AppPalette.spaceMd),
        Text(label, style: Theme.of(context).textTheme.bodyMedium),
      ],
    ),
  );
}

/// A soft, circular icon badge used by the empty/error states.
class _IconBadge extends StatelessWidget {
  const _IconBadge({required this.icon, this.tone});

  final IconData icon;
  final Color? tone;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final color = tone ?? scheme.primary;
    return Container(
      width: 88,
      height: 88,
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        shape: BoxShape.circle,
      ),
      child: Icon(icon, size: 40, color: color),
    );
  }
}

/// A centred error message with a retry button.
class ErrorRetryView extends StatelessWidget {
  /// Creates an error view with a localised [message] and a [retryLabel].
  const ErrorRetryView({
    required this.message,
    required this.retryLabel,
    required this.onRetry,
    super.key,
  });

  /// The localised, user-safe error message.
  final String message;

  /// The localised retry-button label.
  final String retryLabel;

  /// Called when the user taps retry.
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(AppPalette.spaceXl),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          _IconBadge(
            icon: Icons.cloud_off_rounded,
            tone: Theme.of(context).colorScheme.tertiary,
          ),
          const SizedBox(height: AppPalette.spaceLg),
          Text(
            message,
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodyLarge,
          ),
          const SizedBox(height: AppPalette.spaceXl),
          FilledButton.icon(
            onPressed: onRetry,
            icon: const Icon(Icons.refresh_rounded),
            label: Text(retryLabel),
          ),
        ],
      ),
    ),
  );
}

/// A centred empty-state message (icon + text), optionally with a primary action.
class EmptyView extends StatelessWidget {
  /// Creates an empty view with a localised [message].
  ///
  /// [actionLabel] + [onAction], when both supplied, render a primary call to
  /// action under the message (e.g. "Sign in" on the Guest feed prompt).
  const EmptyView({
    required this.message,
    this.icon = Icons.inbox_outlined,
    this.actionLabel,
    this.onAction,
    super.key,
  });

  /// The localised empty-state text.
  final String message;

  /// The illustrative icon.
  final IconData icon;

  /// Optional localised action-button label.
  final String? actionLabel;

  /// Optional action callback (rendered only when [actionLabel] is also set).
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(AppPalette.spaceXl),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          _IconBadge(icon: icon),
          const SizedBox(height: AppPalette.spaceLg),
          Text(
            message,
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodyLarge,
          ),
          if (actionLabel != null && onAction != null) ...[
            const SizedBox(height: AppPalette.spaceXl),
            FilledButton(onPressed: onAction, child: Text(actionLabel!)),
          ],
        ],
      ),
    ),
  );
}
