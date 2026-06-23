/// The signed-in shell: a bottom-nav scaffold hosting the Home/Feed and
/// Find-my-representatives tabs, with a sign-out action.
///
/// Feed and find-my-rep cubits are provided here (lazily) so they live for the
/// shell's lifetime and survive tab switches.
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../core/di/app_dependencies.dart';
import '../../l10n/app_localizations.dart';
import '../auth/bloc/auth_bloc.dart';
import '../auth/bloc/auth_event.dart';
import '../feed/bloc/feed_cubit.dart';
import '../feed/view/feed_screen.dart';
import '../representatives/bloc/my_reps_cubit.dart';
import '../representatives/view/my_reps_screen.dart';

/// The two-tab signed-in shell.
class HomeShell extends StatefulWidget {
  /// Creates the shell over the app [dependencies].
  const HomeShell({required this.dependencies, super.key});

  /// The composition root (for repository access).
  final AppDependencies dependencies;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return MultiBlocProvider(
      providers: [
        BlocProvider<FeedCubit>(
          create: (_) =>
              FeedCubit(repository: widget.dependencies.feedRepository)..load(),
        ),
        BlocProvider<MyRepsCubit>(
          create: (_) => MyRepsCubit(
            repository: widget.dependencies.representativeRepository,
          ),
        ),
      ],
      child: Scaffold(
        appBar: AppBar(
          title: Text(_index == 0 ? l10n.feedTitle : l10n.myRepsTitle),
          actions: [
            IconButton(
              tooltip: l10n.logoutButton,
              icon: const Icon(Icons.logout),
              onPressed: () =>
                  context.read<AuthBloc>().add(const AuthLoggedOut()),
            ),
          ],
        ),
        body: IndexedStack(
          index: _index,
          children: const [FeedScreen(), MyRepsScreen()],
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: _index,
          onDestinationSelected: (i) => setState(() => _index = i),
          destinations: [
            NavigationDestination(
              icon: const Icon(Icons.home_outlined),
              selectedIcon: const Icon(Icons.home),
              label: l10n.navHome,
            ),
            NavigationDestination(
              icon: const Icon(Icons.account_balance_outlined),
              selectedIcon: const Icon(Icons.account_balance),
              label: l10n.navMyReps,
            ),
          ],
        ),
      ),
    );
  }
}
