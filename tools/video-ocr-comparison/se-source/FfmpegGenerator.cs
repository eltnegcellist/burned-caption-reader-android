using System.Diagnostics;
namespace Nikse.SubtitleEdit.Logic.Media;
// Platform process-launch adapter only; timing decisions stay in original SE source.
public static class FfmpegGenerator {
    public static string Executable {get;set;}="ffmpeg";
    public static Process GetProcess(string args,DataReceivedEventHandler handler){
        var p=new Process{StartInfo=new ProcessStartInfo(Executable,args){
            UseShellExecute=false,RedirectStandardOutput=true,RedirectStandardError=true,CreateNoWindow=true}};
        p.ErrorDataReceived+=handler;p.OutputDataReceived+=handler;return p;
    }
}
