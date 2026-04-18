import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'IZIMAT Dictée',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(useMaterial3: true),
      home: const DictationPage(),
    );
  }
}

class DictationPage extends StatefulWidget {
  const DictationPage({super.key});
  @override
  State<DictationPage> createState() => _DictationPageState();
}

class _DictationPageState extends State<DictationPage> {
  static const _channel = EventChannel('speech_continuous');
  StreamSubscription<dynamic>? _subscription;
  bool _isListening = false;
  String _accumulated = '';
  String _current = '';

  void _start() {
    final stream = _channel.receiveBroadcastStream();
    _subscription = stream.listen((event) {
      final map = Map<String, dynamic>.from(event);
      final type = map['type'] as String;
      final text = map['text'] as String? ?? '';
      setState(() {
        if (type == 'partial') {
          _current = text;
        } else if (type == 'final' && text.isNotEmpty) {
          _accumulated += (_accumulated.isEmpty ? '' : ' ') + text;
          _current = '';
        }
      });
    }, onError: (e) {
      setState(() => _isListening = false);
      _subscription = null;
    });
    setState(() {
      _isListening = true;
      _accumulated = '';
      _current = '';
    });
  }

  void _stop() {
    // Capture any in-progress partial text before stopping
    if (_current.isNotEmpty) {
      _accumulated += (_accumulated.isEmpty ? '' : ' ') + _current;
      _current = '';
    }
    // Cancel the subscription — this triggers onCancel in the Kotlin plugin
    _subscription?.cancel();
    _subscription = null;
    setState(() => _isListening = false);
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }

  void _toggle() {
    if (_isListening) _stop(); else _start();
  }

  void _copyText() {
    final text = _accumulated + (_current.isNotEmpty ? ' $_current' : '');
    if (text.isNotEmpty) {
      Clipboard.setData(ClipboardData(text: text));
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Texte copié'), duration: Duration(seconds: 1)),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final isActive = _isListening;
    final displayText = _accumulated + (_current.isNotEmpty ? ' $_current' : '');

    return Scaffold(
      backgroundColor: const Color(0xFFF2EFE9),
      appBar: AppBar(
        backgroundColor: Colors.white,
        surfaceTintColor: Colors.white,
        title: const Text(
          'Dictée',
          style: TextStyle(
            color: Color(0xFF1A1714),
            fontSize: 22,
            fontWeight: FontWeight.w900,
          ),
        ),
        actions: [
          if (displayText.isNotEmpty)
            IconButton(
              icon: const Icon(Icons.copy, color: Color(0xFF7A7470)),
              onPressed: _copyText,
              tooltip: 'Copier',
            ),
          if (displayText.isNotEmpty)
            IconButton(
              icon: const Icon(Icons.delete_outline, color: Color(0xFF7A7470)),
              onPressed: () => setState(() {
                _accumulated = '';
                _current = '';
              }),
              tooltip: 'Effacer',
            ),
        ],
      ),
      body: Column(
        children: [
          // Text area showing dictated text
          Expanded(
            child: Container(
              width: double.infinity,
              margin: const EdgeInsets.fromLTRB(16, 16, 16, 8),
              padding: const EdgeInsets.all(20),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: const Color(0xFFE5E0D8)),
              ),
              child: SingleChildScrollView(
                reverse: true,
                child: Text(
                  displayText.isEmpty
                      ? 'Appuyez sur le micro pour commencer...'
                      : displayText,
                  style: TextStyle(
                    fontSize: 18,
                    height: 1.6,
                    color: displayText.isEmpty
                        ? const Color(0xFFB8B3B0)
                        : const Color(0xFF1A1714),
                  ),
                ),
              ),
            ),
          ),
          // Live partial text indicator
          Container(
            width: double.infinity,
            margin: const EdgeInsets.symmetric(horizontal: 16),
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: isActive ? const Color(0xFFFDF0EC) : Colors.white,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: isActive ? const Color(0xFFC8431A) : const Color(0xFFE5E0D8),
              ),
            ),
            child: Text(
              _current.isNotEmpty
                  ? _current
                  : (isActive ? 'En écoute...' : '—'),
              style: TextStyle(
                fontSize: 16,
                color: isActive ? const Color(0xFFC8431A) : const Color(0xFFB8B3B0),
                fontWeight: isActive ? FontWeight.w600 : FontWeight.normal,
              ),
            ),
          ),
          const SizedBox(height: 24),
          // Start/Stop button
          GestureDetector(
            onTap: _toggle,
            child: Container(
              width: 88,
              height: 88,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: isActive ? const Color(0xFFC8431A) : Colors.white,
                border: Border.all(color: const Color(0xFFC8431A), width: 3),
                boxShadow: isActive
                    ? [
                        BoxShadow(
                          color: const Color(0xFFC8431A).withOpacity(0.3),
                          blurRadius: 20,
                          spreadRadius: 4,
                        ),
                      ]
                    : null,
              ),
              child: Icon(
                isActive ? Icons.stop_rounded : Icons.mic,
                size: 40,
                color: isActive ? Colors.white : const Color(0xFFC8431A),
              ),
            ),
          ),
          const SizedBox(height: 40),
        ],
      ),
    );
  }
}
