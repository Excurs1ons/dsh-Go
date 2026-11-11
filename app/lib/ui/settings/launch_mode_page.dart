// The start-path chooser: Direct, compatible, root mode and Shizuku, plus the root
// system's own copy (create / re-sync / delete) and its authorization state.

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../runtime/runtime_bridge.dart';
import '../../runtime/runtime_state.dart';
import '../common/brief_notice.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import 'settings_widgets.dart';

/// What each start path is called in the UI.
String launchModeLabel(L10n l10n, String mode) => switch (mode) {
  'compat' => l10n.launchModeCompat,
  'direct' => l10n.launchModeDirect,
  'root' => l10n.launchModeRoot,
  'shizuku' => l10n.launchModeShizuku,
  _ => l10n.launchModeAuto,
};

String launchModeHint(L10n l10n, String mode) => switch (mode) {
  'compat' => l10n.launchModeCompatHint,
  'direct' => l10n.launchModeDirectHint,
  'root' => l10n.launchModeRootHint,
  'shizuku' => l10n.launchModeShizukuHint,
  _ => l10n.launchModeAutoHint,
};

/// The entry row in 开发者选项.
class LaunchModeTile extends ConsumerWidget {
  const LaunchModeTile({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = context.l10n;
    final status = ref.watch(localRuntimeProvider).status;
    return SettingsTile(
      key: const ValueKey('settings-launch-mode'),
      icon: AppIcons.configure,
      title: l10n.launchModeTitle,
      subtitle: launchModeHint(l10n, status.launchMode),
      value: Text(launchModeLabel(l10n, status.launchMode)),
      onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const LaunchModePage())),
    );
  }
}

class LaunchModePage extends ConsumerStatefulWidget {
  const LaunchModePage({super.key});

  @override
  ConsumerState<LaunchModePage> createState() => _LaunchModePageState();
}

class _LaunchModePageState extends ConsumerState<LaunchModePage> {
  static const _modes = ['auto', 'compat', 'direct', 'root', 'shizuku'];

  bool _saving = false;
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final status = ref.watch(localRuntimeProvider).status;
    final stopped = !status.phase.isBusy && status.phase != RuntimePhase.running;
    final editable = stopped && !_saving && !_busy;
    return AppPage.list(
      title: l10n.launchModeTitle,
      children: [
        SettingsSection(
          footer: Text(editable ? l10n.launchModeHint : l10n.launchModeStopFirst),
          children: [
            for (final mode in _modes)
              Semantics(
                checked: status.launchMode == mode,
                inMutuallyExclusiveGroup: true,
                child: SettingsTile(
                  key: ValueKey('launch-mode-$mode'),
                  title: launchModeLabel(l10n, mode),
                  subtitle: launchModeHint(l10n, mode),
                  trailing: SizedBox.square(
                    dimension: 32,
                    child: Icon(status.launchMode == mode ? AppIcons.radioOn : AppIcons.radioOff, size: 24),
                  ),
                  onTap: editable ? () => _setMode(mode) : null,
                ),
              ),
          ],
        ),
        SettingsSection(
          title: l10n.launchModeRootSection,
          footer: Text(_rootCopyFooter(l10n, status.rootCopy)),
          children: [
            SettingsTile(
              title: _rootCopyLabel(l10n, status.rootCopy),
              subtitle: status.rootCopy == 'missing' || status.rootCopy == 'stale'
                  ? l10n.launchModeRootCreateHint
                  : null,
              trailing: _busy || status.rootCopy == 'copying'
                  ? const SizedBox.square(dimension: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : null,
              onTap: editable && (status.rootCopy == 'missing' || status.rootCopy == 'stale')
                  ? () => _createRootCopy(overwrite: status.rootCopy == 'stale')
                  : null,
            ),
            SettingsTile(
              title: _rootStateLabel(l10n, status.rootState),
              subtitle: status.rootDetail,
              trailing: Text(status.rootAvailable ? l10n.launchModeRootDetected : l10n.launchModeRootNotDetected),
              onTap: editable && status.rootAvailable ? _probeRoot : null,
            ),
            if (status.rootCopy != 'missing')
              SettingsTile(
                title: l10n.launchModeRootDelete,
                danger: true,
                onTap: editable ? _deleteRootCopy : null,
              ),
          ],
        ),
      ],
    );
  }

  String _rootCopyLabel(L10n l10n, String copy) => switch (copy) {
    'ready' => l10n.launchModeRootReady,
    'stale' => l10n.launchModeRootStale,
    'copying' => l10n.launchModeRootCopying,
    _ => l10n.launchModeRootMissing,
  };

  String _rootCopyFooter(L10n l10n, String copy) => switch (copy) {
    'stale' => l10n.launchModeRootOverwrite,
    _ => l10n.launchModeRootCreateHint,
  };

  String _rootStateLabel(L10n l10n, String state) => switch (state) {
    'authorized' => l10n.launchModeRootAuthorized,
    'denied' => l10n.launchModeRootDenied,
    'timeout' => l10n.launchModeRootTimeout,
    _ => l10n.launchModeRootAuthorize,
  };

  Future<void> _setMode(String mode) async {
    setState(() => _saving = true);
    try {
      await ref.read(runtimeBridgeProvider).setLaunchMode(mode);
    } catch (_) {
      if (mounted) showBriefNotice(context, context.l10n.launchModeChangeFailed);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _probeRoot() async {
    setState(() => _busy = true);
    try {
      await ref.read(runtimeBridgeProvider).probeRoot();
    } catch (_) {
      if (mounted) showBriefNotice(context, context.l10n.launchModeChangeFailed);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _createRootCopy({required bool overwrite}) async {
    final l10n = context.l10n;
    if (overwrite) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          content: Text(l10n.launchModeRootOverwrite),
          actions: [
            TextButton(onPressed: () => Navigator.of(context).pop(false), child: Text(l10n.commonCancel)),
            TextButton(onPressed: () => Navigator.of(context).pop(true), child: Text(l10n.launchModeRootResync)),
          ],
        ),
      );
      if (confirmed != true) return;
    }
    if (!mounted) return;
    setState(() => _busy = true);
    try {
      await ref.read(runtimeBridgeProvider).createRootCopy();
      if (mounted) showBriefNotice(context, l10n.launchModeRootCopied);
    } catch (_) {
      if (mounted) showBriefNotice(context, l10n.launchModeRootFailed);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _deleteRootCopy() async {
    final l10n = context.l10n;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        content: Text(l10n.launchModeRootDeleteConfirm),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: Text(l10n.commonCancel)),
          TextButton(onPressed: () => Navigator.of(context).pop(true), child: Text(l10n.launchModeRootDelete)),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() => _busy = true);
    try {
      await ref.read(runtimeBridgeProvider).deleteRootCopy();
    } catch (_) {
      if (mounted) showBriefNotice(context, l10n.launchModeRootFailed);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}
