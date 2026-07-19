import Foundation

struct LyricsLine {
    /// Seconds from track start, or -1 if this line has no LRC timestamp.
    let time: TimeInterval
    let text: String
}

struct ParsedLyrics {
    let lines: [LyricsLine]
    let isTimeSynced: Bool
}

/// Parses raw LRC-tagged (or plain) lyrics text, mirroring the PWA's
/// packages/core/src/lyrics/lrc-parser.ts exactly so both clients treat the
/// same file the same way.
enum LrcParser {
    private static let timestampRegex = try! NSRegularExpression(pattern: #"\[(\d{1,3}):(\d{2})(?:\.(\d{2,3}))?\]"#)
    private static let metadataRegex = try! NSRegularExpression(
        pattern: #"^\[[a-zA-Z]{2}:.+\]$"#
    )

    static func parse(_ raw: String) -> ParsedLyrics {
        var lines: [LyricsLine] = []
        var hasTimestamps = false

        for substring in raw.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = String(substring)
            let fullRange = NSRange(line.startIndex..<line.endIndex, in: line)

            if metadataRegex.firstMatch(in: line, range: fullRange) != nil {
                continue
            }

            let matches = timestampRegex.matches(in: line, range: fullRange)
            guard !matches.isEmpty else {
                let trimmed = line.trimmingCharacters(in: .whitespaces)
                if !trimmed.isEmpty {
                    lines.append(LyricsLine(time: -1, text: trimmed))
                }
                continue
            }

            hasTimestamps = true
            let lastMatch = matches[matches.count - 1]
            guard let textStart = Range(lastMatch.range, in: line)?.upperBound else { continue }
            let text = String(line[textStart...]).trimmingCharacters(in: .whitespaces)

            for match in matches {
                guard let minutesRange = Range(match.range(at: 1), in: line),
                      let secondsRange = Range(match.range(at: 2), in: line),
                      let minutes = Double(line[minutesRange]),
                      let seconds = Double(line[secondsRange]) else { continue }
                var fraction: Double = 0
                if match.range(at: 3).location != NSNotFound, let fractionRange = Range(match.range(at: 3), in: line) {
                    var fractionStr = String(line[fractionRange])
                    while fractionStr.count < 3 { fractionStr += "0" }
                    fraction = (Double(fractionStr) ?? 0) / 1000
                }
                lines.append(LyricsLine(time: minutes * 60 + seconds + fraction, text: text))
            }
        }

        // A mix of timed and untimed lines is NOT treated as synced — matches PWA behavior.
        let isTimeSynced = hasTimestamps && lines.allSatisfy { $0.time >= 0 }
        if hasTimestamps {
            lines.sort { $0.time < $1.time }
        }
        return ParsedLyrics(lines: lines, isTimeSynced: isTimeSynced)
    }

    /// The last line whose timestamp is <= currentTime, or nil if before the first line.
    static func activeLineIndex(_ lines: [LyricsLine], currentTime: TimeInterval) -> Int? {
        guard !lines.isEmpty else { return nil }
        var lo = 0, hi = lines.count - 1, result = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if lines[mid].time <= currentTime {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result >= 0 ? result : nil
    }
}
