package com.contextcompresso.vatstash.scan;

import java.util.List;

public record ScanResult(List<ScannedFile> files, int skipped, boolean truncated) {
}
