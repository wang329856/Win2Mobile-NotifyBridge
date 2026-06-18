using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using NotificationService.Services;
using Serilog;

namespace NotificationService;

static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();

        Log.Logger = new LoggerConfiguration()
            .MinimumLevel.Debug()
            .WriteTo.File("logs/service-.log", rollingInterval: RollingInterval.Day)
            .CreateLogger();

        try
        {
            Application.Run(new MainForm());
        }
        catch (Exception ex)
        {
            Log.Fatal(ex, "Application terminated");
        }
        finally
        {
            Log.CloseAndFlush();
        }
    }
}
