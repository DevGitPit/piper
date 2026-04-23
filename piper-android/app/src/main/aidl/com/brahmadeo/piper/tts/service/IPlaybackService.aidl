package com.brahmadeo.piper.tts.service;

import com.brahmadeo.piper.tts.service.IPlaybackListener;

interface IPlaybackService {
    oneway void synthesizeAndPlay(String text, String lang, float speed, int startIndex);
    oneway void addToQueue(String text, String lang, float speed, int startIndex);
    oneway void play();
    oneway void pause();
    oneway void stop();
    boolean isServiceActive();
    oneway void setListener(IPlaybackListener listener);
    oneway void exportAudio(String text, String lang, float speed, String outputPath);
    int getCurrentIndex();
}
