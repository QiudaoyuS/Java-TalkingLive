' ============================================================
'  TalkingLive silent launcher (double-click this file)
'
'  Why this exists: run.cmd is a batch file, and a batch file always
'  runs inside a console window. `start /b` does NOT get rid of it --
'  /b means "start in the SAME console", so the window survives the
'  script and stays on the desktop forever.
'
'  WScript.Shell.Run with intWindowStyle = 0 is the one Windows
'  mechanism that creates NO console window at all. So the window is
'  suppressed here, at the top of the process tree, and run.cmd keeps
'  its job of validating the JDK / building / reporting errors.
'
'  If the app is not built yet, run.cmd would need a visible console
'  (a first build takes 1-2 minutes and must show progress), so this
'  launcher falls back to showing it in that case.
' ============================================================

Option Explicit

Dim fso, shell, root, jar, cmd
Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

root = fso.GetParentFolderName(WScript.ScriptFullName)
jar = fso.BuildPath(root, "target\talkinglive.jar")

If Not fso.FileExists(jar) Then
    ' Not built yet: show the console so the user can see the build and
    ' any error message. Silence would just look like "nothing happened".
    shell.CurrentDirectory = root
    shell.Run """" & fso.BuildPath(root, "run.cmd") & """", 1, False
    WScript.Quit 0
End If

' 0 = hidden window, False = do not wait (the app is a background tray app)
shell.CurrentDirectory = root
cmd = """" & fso.BuildPath(root, "run.cmd") & """"
shell.Run cmd, 0, False

Set shell = Nothing
Set fso = Nothing
