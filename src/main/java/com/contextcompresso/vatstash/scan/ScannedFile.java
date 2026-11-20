package com.contextcompresso.vatstash.scan;

import java.nio.file.Path;

public record ScannedFile(String rel, Path abs, long size, long mtimeMs, String language) {
}
