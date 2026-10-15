package com.pocketmcp.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandSafetyTest {

    @Test
    fun `mainstream destructive commands are blocked`() {
        assertTrue(isBlockedShellCommand("rm -rf /sdcard/dcim"))
        assertTrue(isBlockedShellCommand("rm -fr /data/local/tmp/x"))
        assertTrue(isBlockedShellCommand("rm -r -f /sdcard"))
        assertTrue(isBlockedShellCommand("rm -f -r /sdcard"))
        assertTrue(isBlockedShellCommand("sudo rm -rf /"))
        assertTrue(isBlockedShellCommand("echo hi && rm -rf /sdcard"))
        assertTrue(isBlockedShellCommand("mkfs.ext4 /dev/block/sda1"))
        assertTrue(isBlockedShellCommand("dd if=/dev/zero of=/dev/block/sda"))
        assertTrue(isBlockedShellCommand("reboot"))
        assertTrue(isBlockedShellCommand("sudo shutdown -h now"))
        assertTrue(isBlockedShellCommand("poweroff"))
        assertTrue(isBlockedShellCommand("halt"))
        assertTrue(isBlockedShellCommand("fastboot flash boot boot.img"))
        assertTrue(isBlockedShellCommand("pm uninstall com.example.app"))
        assertTrue(isBlockedShellCommand("setenforce 0"))
        assertTrue(isBlockedShellCommand("mount -o remount,rw /system"))
    }

    @Test
    fun `newline injection is blocked`() {
        assertTrue(isBlockedShellCommand("ls\nrm -rf /"))
        assertTrue(isBlockedShellCommand("ls\rrm -rf /"))
    }

    @Test
    fun `harmless commands are allowed`() {
        assertFalse(isBlockedShellCommand("ls -la /sdcard"))
        assertFalse(isBlockedShellCommand("df -h"))
        assertFalse(isBlockedShellCommand("echo hello world"))
        assertFalse(isBlockedShellCommand("rm notes.txt"))
        assertFalse(isBlockedShellCommand("rm -i notes.txt"))
        assertFalse(isBlockedShellCommand("rm -f notes.txt"))
        assertFalse(isBlockedShellCommand("rm -r old-folder"))
        assertFalse(isBlockedShellCommand("getprop ro.build.version.release"))
    }
}
