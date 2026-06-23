/// Small, reusable state widgets for the load/empty/error states every data
/// screen must handle (CLAUDE.md: empty/error/offline states are first-class).
library;

import 'package:flutter/material.dart';

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
        const CircularProgressIndicator(),
        const SizedBox(height: 12),
        Text(label),
      ],
    ),
  );
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
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.cloud_off,
            size: 48,
            color: Theme.of(context).colorScheme.outline,
          ),
          const SizedBox(height: 12),
          Text(message, textAlign: TextAlign.center),
          const SizedBox(height: 16),
          FilledButton(onPressed: onRetry, child: Text(retryLabel)),
        ],
      ),
    ),
  );
}

/// A centred empty-state message (icon + text).
class EmptyView extends StatelessWidget {
  /// Creates an empty view with a localised [message].
  const EmptyView({required this.message, this.icon = Icons.inbox, super.key});

  /// The localised empty-state text.
  final String message;

  /// The illustrative icon.
  final IconData icon;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 48, color: Theme.of(context).colorScheme.outline),
          const SizedBox(height: 12),
          Text(message, textAlign: TextAlign.center),
        ],
      ),
    ),
  );
}
