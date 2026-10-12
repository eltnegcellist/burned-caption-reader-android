using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;
using SkiaSharp;
using Nikse.SubtitleEdit.Features.Ocr.Engines;

// JSON/file adapter. Recognition and spatial line composition stay in official SE files.
internal static class ImageExperiment {
    static string Hash(byte[] raw)=>Convert.ToHexString(SHA256.HashData(raw)).ToLowerInvariant();
    internal static void Run(string manifestName,string output,string scope){
        if(scope!="manual-roi" && scope!="full-frame-engine")throw new ArgumentException("Invalid scope");
        if(!AppleVisionOcr.IsAvailable())throw new Exception("Apple Vision unavailable");
        var manifest=Path.GetFullPath(manifestName);var raw=File.ReadAllBytes(manifest);
        using var json=JsonDocument.Parse(raw);var data=json.RootElement;
        if(!data.GetProperty("reference_verified_from_pixels").GetBoolean())throw new Exception("Independent pixel review required");
        var root=Path.GetDirectoryName(manifest)!;var results=new List<object>();
        foreach(var f in data.GetProperty("frames").EnumerateArray()){
            var path=Path.GetFullPath(Path.Combine(root,f.GetProperty("image").GetString()!));
            if(!path.StartsWith(root+Path.DirectorySeparatorChar,StringComparison.Ordinal))throw new Exception("Image outside dataset");
            var bytes=File.ReadAllBytes(path);if(Hash(bytes)!=f.GetProperty("sha256").GetString())throw new Exception("Image hash changed");
            using var bitmap=SKBitmap.Decode(bytes);if(bitmap==null)throw new Exception("Image decode failed");
            int[]? roi=null;SKBitmap input=bitmap;
            if(scope=="manual-roi"){
                roi=data.GetProperty("common_roi_pixels").EnumerateArray().Select(v=>v.GetInt32()).ToArray();
                input=new SKBitmap(roi[2],roi[3],bitmap.ColorType,bitmap.AlphaType);
                if(!bitmap.ExtractSubset(input,new SKRectI(roi[0],roi[1],roi[0]+roi[2],roi[1]+roi[3])))throw new Exception("Invalid ROI");
            }
            try{
                var rgb=new byte[input.Width*input.Height*3];int index=0;
                for(int y=0;y<input.Height;y++)for(int x=0;x<input.Width;x++){var c=input.GetPixel(x,y);rgb[index++]=c.Red;rgb[index++]=c.Green;rgb[index++]=c.Blue;}
                var clock=Stopwatch.StartNew();
                var observations=AppleVisionOcr.OcrObservations(input,"ja-JP",false,CancellationToken.None);
                var text=AppleVisionTextLayout.Compose(observations);var elapsed=clock.Elapsed.TotalMilliseconds;
                var naive=string.Join("\n",observations.OrderByDescending(v=>v.Top).ThenBy(v=>v.Left).Select(v=>v.Text));
                results.Add(new{id=f.GetProperty("id").GetString(),sha256=Hash(bytes),roi,roi_rgb_sha256=Hash(rgb),
                    expected_text=f.GetProperty("expected_text").GetString(),text,ocr_ms=elapsed,
                    raw_naive_y_sort_text=naive,rows=observations.Select(v=>new{text=v.Text,normalized_box=new[]{v.Left,1-v.Top,v.Right-v.Left,v.Top-v.Bottom}}).ToArray()});
            }finally{if(input!=bitmap)input.Dispose();}
        }
        var result=new{engine="subtitleedit-vision-source-layout",scope,source=data.GetProperty("source").GetString(),
            pixelcopy=true,product_pipeline=false,gui_product_run=false,manifest_sha256=Hash(raw),
            caveat="Original SE AppleVisionOcr and AppleVisionTextLayout; no brightness filter or GUI recovery. Detector plus recognizer, not pure decoder.",frames=results};
        File.WriteAllText(output,JsonSerializer.Serialize(result,new JsonSerializerOptions{WriteIndented=true})+"\n");
        Console.WriteLine($"SE official Vision/layout images complete: {results.Count}");
    }
}
