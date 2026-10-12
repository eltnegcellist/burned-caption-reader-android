using System.Diagnostics;
using System.Text.Json;
using SkiaSharp;
using Nikse.SubtitleEdit.Features.Ocr.Engines;
using Nikse.SubtitleEdit.Features.Video.VideoOcr;

// A source-based component experiment, NOT the GUI Video OCR product run.
// Links eight unchanged official SE v5.2.0 source files. No new OCR/grouping algorithm.
if (args.Length < 4 || args.Length > 5) throw new ArgumentException("ffmpeg video output.srt frames-folder [refine]");
if (!AppleVisionOcr.IsAvailable()) throw new Exception("Apple Vision unavailable");
var framesFolder=Path.GetFullPath(args[3]);Directory.CreateDirectory(framesFolder);
var start=Stopwatch.StartNew();
var psi=new ProcessStartInfo(args[0]){UseShellExecute=false,RedirectStandardError=true};
foreach(var s in new[]{"-hide_banner","-loglevel","error","-y","-i",args[1],"-vf","crop=960:190:0:350,scale=720:-2,fps=5","-start_number","0",Path.Combine(framesFolder,"img%06d.jpg")})psi.ArgumentList.Add(s);
using(var proc=Process.Start(psi)!){var error=proc.StandardError.ReadToEnd();proc.WaitForExit();if(proc.ExitCode!=0)throw new Exception(error);}
var files=Directory.GetFiles(framesFolder,"img*.jpg").OrderBy(p=>p).ToArray();
var groups=VideoOcrFrameGrouper.Group(files,190,92,null,CancellationToken.None);
var readings=new List<object>();int calls=0;
foreach(var group in groups.Where(p=>!p.IsBlank)){
    using var bitmap=SKBitmap.Decode(group.RepresentativeFileName);
    var clock=Stopwatch.StartNew();
    var observations=AppleVisionOcr.OcrObservations(bitmap,"ja-JP",false,CancellationToken.None);
    var kept=VideoOcrObservationFilter.FilterByBrightness(observations,bitmap,190);
    group.Text=AppleVisionTextLayout.Compose(kept);calls++;
    readings.Add(new{group.StartFrame,group.EndFrame,group.Text,ocr_ms=clock.Elapsed.TotalMilliseconds});
}
var lines=VideoOcrLineBuilder.Build(groups,5,80,250,250);
var coarseLines=lines.Select(p=>new{p.StartMs,p.EndMs,p.Text}).ToArray();
if(args.Length==5 && args[4]=="refine"){
    Nikse.SubtitleEdit.Logic.Media.FfmpegGenerator.Executable=Path.GetFullPath(args[0]);
    await VideoOcrTimingRefiner.RefineAsync(lines,new VideoOcrTimingRefiner.Context{
        VideoFileName=Path.GetFullPath(args[1]),FramesFolder=framesFolder,CoarseFps=5,
        BrightnessMinimum=190,ImageSimilarityPercent=92,CropAndScaleFilter="crop=960:190:0:350,scale=720:-2"
    },null,CancellationToken.None);
}
string Stamp(double ms)=>TimeSpan.FromMilliseconds(ms).ToString(@"hh\:mm\:ss\,fff");
File.WriteAllText(args[2],string.Join("\n\n",lines.Select((p,i)=>$"{i+1}\n{Stamp(p.StartMs)} --> {Stamp(p.EndMs)}\n{p.Text}"))+"\n");
var result=new{experiment="owned_synthetic_SE_source_components",gui_product_run=false,
    omitted="GUI/viewmodel recovery and spelling fixes are not exercised; refinement only with explicit refine argument",
    refinement=args.Length==5,coarseLines,
    fps=5,brightness_minimum=190,image_similarity_percent=92,text_similarity_percent=80,
    max_gap_ms=250,min_duration_ms=250,sampled_frames=files.Length,group_count=groups.Count,
    ocr_calls=calls,non_ocr_sampled_frames=files.Length-calls,elapsed_seconds=start.Elapsed.TotalSeconds,readings};
File.WriteAllText(Path.ChangeExtension(args[2],"source.json"),JsonSerializer.Serialize(result,new JsonSerializerOptions{WriteIndented=true}));
Console.WriteLine(JsonSerializer.Serialize(result));
