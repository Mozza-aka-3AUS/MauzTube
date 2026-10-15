package javax.sound.sampled;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Sostituto minimale di javax.sound.sampled.AudioFormat (assente su Android), con i soli
 * membri usati dal codificatore MP3 jump3r (de.sciss.jump3r.lowlevel.LameEncoder).
 * Semplice contenitore di dati: nessuna funzionalità audio.
 */
public class AudioFormat {

    public static class Encoding {
        public static final Encoding PCM_SIGNED = new Encoding("PCM_SIGNED");
        public static final Encoding PCM_UNSIGNED = new Encoding("PCM_UNSIGNED");

        private final String name;

        public Encoding(String name) { this.name = name; }

        @Override public String toString() { return name; }
        @Override public boolean equals(Object o) { return o instanceof Encoding && ((Encoding) o).name.equals(name); }
        @Override public int hashCode() { return name.hashCode(); }
    }

    private final Encoding encoding;
    private final float sampleRate;
    private final int sampleSizeInBits;
    private final int channels;
    private final int frameSize;
    private final float frameRate;
    private final boolean bigEndian;
    private final Map<String, Object> properties;

    public AudioFormat(Encoding encoding, float sampleRate, int sampleSizeInBits, int channels,
                       int frameSize, float frameRate, boolean bigEndian, Map<String, Object> properties) {
        this.encoding = encoding;
        this.sampleRate = sampleRate;
        this.sampleSizeInBits = sampleSizeInBits;
        this.channels = channels;
        this.frameSize = frameSize;
        this.frameRate = frameRate;
        this.bigEndian = bigEndian;
        this.properties = properties == null ? new HashMap<>() : new HashMap<>(properties);
    }

    public AudioFormat(Encoding encoding, float sampleRate, int sampleSizeInBits, int channels,
                       int frameSize, float frameRate, boolean bigEndian) {
        this(encoding, sampleRate, sampleSizeInBits, channels, frameSize, frameRate, bigEndian, null);
    }

    public Encoding getEncoding() { return encoding; }
    public float getSampleRate() { return sampleRate; }
    public int getSampleSizeInBits() { return sampleSizeInBits; }
    public int getChannels() { return channels; }
    public int getFrameSize() { return frameSize; }
    public float getFrameRate() { return frameRate; }
    public boolean isBigEndian() { return bigEndian; }
    public Map<String, Object> properties() { return Collections.unmodifiableMap(properties); }
}
