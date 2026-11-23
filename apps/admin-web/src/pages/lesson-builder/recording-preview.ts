export async function recordingPreview(file: File): Promise<Blob> {
  // Chromium's MediaRecorder WebM has no duration metadata until the browser reads to the end.
  if (!file.type.includes('webm') || typeof AudioContext === 'undefined') return file;
  const context = new AudioContext();
  try {
    const audio = await context.decodeAudioData(await file.arrayBuffer());
    const channels = Array.from({ length: audio.numberOfChannels }, (_, index) => audio.getChannelData(index));
    const samples = audio.length;
    const wav = new DataView(new ArrayBuffer(44 + samples * 2));
    wav.setUint32(0, 0x52494646);
    wav.setUint32(4, 36 + samples * 2, true);
    wav.setUint32(8, 0x57415645);
    wav.setUint32(12, 0x666d7420);
    wav.setUint32(16, 16, true);
    wav.setUint16(20, 1, true);
    wav.setUint16(22, 1, true);
    wav.setUint32(24, audio.sampleRate, true);
    wav.setUint32(28, audio.sampleRate * 2, true);
    wav.setUint16(32, 2, true);
    wav.setUint16(34, 16, true);
    wav.setUint32(36, 0x64617461);
    wav.setUint32(40, samples * 2, true);
    for (let index = 0; index < samples; index++) {
      const value = channels.reduce((sum, channel) => sum + channel[index], 0) / channels.length;
      wav.setInt16(44 + index * 2, Math.round(Math.max(-32768, Math.min(32767, value * 32768))), true);
    }
    return new Blob([wav.buffer], { type: 'audio/wav' });
  } finally {
    await context.close();
  }
}
