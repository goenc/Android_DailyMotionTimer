import argparse
import wave


def read_samples(path):
    with wave.open(path, "rb") as wav_file:
        channels = wav_file.getnchannels()
        sample_width = wav_file.getsampwidth()
        sample_rate = wav_file.getframerate()
        frames = wav_file.readframes(wav_file.getnframes())

    if channels != 1:
        raise ValueError("Only mono wav files are supported")
    if sample_width != 2:
        raise ValueError("Only 16-bit wav files are supported")

    samples = [
        int.from_bytes(frames[index:index + sample_width], "little", signed=True)
        for index in range(0, len(frames), sample_width)
    ]
    return sample_rate, sample_width, samples


def trim_silence(samples, sample_rate, threshold, padding_ms):
    start = 0
    while start < len(samples) and abs(samples[start]) < threshold:
        start += 1

    end = len(samples) - 1
    while end >= start and abs(samples[end]) < threshold:
        end -= 1

    if start > end:
        return samples

    padding_samples = int(sample_rate * padding_ms / 1000)
    start = max(0, start - padding_samples)
    end = min(len(samples), end + padding_samples + 1)
    return samples[start:end]


def normalize(samples, peak_target, max_gain):
    peak = max((abs(sample) for sample in samples), default=1)
    gain = min(max_gain, peak_target / peak) if peak else 1.0
    return [
        max(-32768, min(32767, int(round(sample * gain))))
        for sample in samples
    ]


def write_samples(path, sample_rate, sample_width, samples):
    frames = b"".join(
        int(sample).to_bytes(sample_width, "little", signed=True)
        for sample in samples
    )
    with wave.open(path, "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(sample_width)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(frames)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source")
    parser.add_argument("destination")
    parser.add_argument("--threshold", type=int, default=450)
    parser.add_argument("--padding-ms", type=int, default=8)
    parser.add_argument("--peak-target", type=int, default=24500)
    parser.add_argument("--max-gain", type=float, default=1.45)
    args = parser.parse_args()

    sample_rate, sample_width, samples = read_samples(args.source)
    trimmed = trim_silence(samples, sample_rate, args.threshold, args.padding_ms)
    normalized = normalize(trimmed, args.peak_target, args.max_gain)
    write_samples(args.destination, sample_rate, sample_width, normalized)


if __name__ == "__main__":
    main()
