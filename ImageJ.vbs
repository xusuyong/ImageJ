Set fso = CreateObject("Scripting.FileSystemObject")
scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
jarPath = scriptDir & "\ij.jar"

javaExe = "C:\Program Files\RedHat\java-1.8.0-openjdk-1.8.0.504-1\bin\javaw.exe"
If Not fso.FileExists(javaExe) Then
    javaExe = "javaw.exe"
End If

args = ""
For i = 0 To WScript.Arguments.Count - 1
    args = args & " """ & WScript.Arguments(i) & """"
Next

cmd = """" & javaExe & """ -Xmx16g -jar """ & jarPath & """" & args

Set shell = CreateObject("WScript.Shell")
shell.CurrentDirectory = scriptDir
shell.Run cmd, 0, False
