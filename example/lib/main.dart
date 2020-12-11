import 'package:flutter/material.dart';
import 'package:flutter_plugin_audio/flutter_plugin_audio.dart';



void main() => runApp(MyApp());

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  var _audio = NativeAudio();
  var _progressText = "0:00:00";
  var _isLoaded = false;
  var _isPlaying = false;
  var _status = "stopped";

  @override
  void initState() {
    super.initState();

    _audio.setSkipTime(
        forwardTime: Duration(seconds: 30),
        backwardTime: Duration(seconds: 10));
    _listenForAudioEvents();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Native Audio'),
        ),
        body: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Text(_progressText, textAlign: TextAlign.center),
            Padding(
              padding: EdgeInsets.all(16.0),
              child: Text(_status, textAlign: TextAlign.center),
            ),
            SizedBox(height: 48),
            if (!_isLoaded)
              MaterialButton(
                  child: Text("Play"), onPressed: () => _playSampleAudio()),
            if (_isLoaded)
              MaterialButton(
                  child: Text("Stop"), onPressed: () => _audio.stop()),
            if (!_isPlaying && _isLoaded)
              MaterialButton(
                  child: Text("Resume"), onPressed: () => _audio.resume()),
            if (_isPlaying)
              MaterialButton(
                  child: Text("Pause"), onPressed: () => _audio.pause()),
            if (_isLoaded)
              MaterialButton(
                  child: Text("Seek to 30m"),
                  onPressed: () => _audio.seekTo(Duration(minutes: 30))),
            if (_isLoaded)
              MaterialButton(
                  child: Text("Seek to 70m"),
                  onPressed: () => _audio.seekTo(Duration(minutes: 70))),
            if (_isLoaded)
              MaterialButton(
                  child: Text("Skip Forward"),
                  onPressed: () => _audio.skipForward()),
            if (_isLoaded)
              MaterialButton(
                  child: Text("Skip Backward"),
                  onPressed: () => _audio.skipBackward()),
          ],
        ),
      ),
    );
  }

  void _listenForAudioEvents() {
    _audio.onLoaded = (totalDuration, startedAutomatically) {
      setState(() {
        _isLoaded = true;
        _isPlaying = startedAutomatically;
        _status = "loaded";
      });
    };

    _audio.onResumed = () {
      setState(() => _isPlaying = true);
      _status = "resumed";
    };

    _audio.onPaused = () {
      setState(() {
        _isPlaying = false;
        _status = "paused";
      });
    };

    _audio.onStopped = () {
      setState(() {
        _isLoaded = false;
        _isPlaying = false;
        _status = "stopped";
      });
    };

    _audio.onCompleted = () {
      setState(() {
        _isLoaded = false;
        _isPlaying = false;
        _status = "completed";
      });
    };

    _audio.onProgressChanged = (progress) {
      setState(() {
        this._progressText = _durationToString(progress);
      });
    };
  }

  void _playSampleAudio() {
    setState(() => _status = "loading");
    _audio.play(
        "https://vod.rockerzs.com/audio/89cb637d060d450e955618ed1d68994d/master.m3u8?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=20201110T180335Z&X-Amz-SignedHeaders=host&X-Amz-Expires=-1605013175&X-Amz-Credential=AKIAUWNXUSSJSRVE443K%2F20201110%2Fme-south-1%2Fs3%2Faws4_request&X-Amz-Signature=c63e9fcbd758c0f61eeeaf7fbc4126c5b9d0c56980c7fa1d6aa2232a3fba76e8",
        title: "How The Fashion Industry Is Responding To Climate Change",
        album: "Science Friday",
        artist: "WNYC Studio",
        imageUrl:
        "https://www.sciencefriday.com/wp-content/uploads/2019/09/clothes-close-min.jpg");
  }

  String _durationToString(Duration duration) {
    String twoDigits(int n) {
      if (n >= 10) return "$n";
      return "0$n";
    }

    String twoDigitMinutes = twoDigits(duration.inMinutes.remainder(60));
    String twoDigitSeconds = twoDigits(duration.inSeconds.remainder(60));
    return "${twoDigits(duration.inHours)}:$twoDigitMinutes:$twoDigitSeconds";
  }
}
