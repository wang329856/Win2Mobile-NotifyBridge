using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace Win2Mobile.Transport.Lan;

public static class CertificateManager
{
    /// <summary>
    /// DPAPI protects the persistent PFX. UserKeySet creates a Schannel-compatible temporary
    /// Windows key container, released when the caller disposes the returned certificate.
    /// </summary>
    public static X509Certificate2 GetOrCreate(string dataDirectory)
    {
        if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("Persistent bridge certificates require Windows DPAPI.");
        var directory = Path.TrimEndingDirectorySeparator(Path.GetFullPath(dataDirectory));
        var lockId = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(directory.ToUpperInvariant())));
        // Global names serialize callers in different logon sessions of the same Windows user.
        using var mutex = new Mutex(false, @"Global\Win2Mobile.Certificate." + lockId);
        bool acquired = false;
        try
        {
            try { acquired = mutex.WaitOne(TimeSpan.FromSeconds(30)); }
            catch (AbandonedMutexException) { acquired = true; }
            if (!acquired) throw new TimeoutException("Another process is initializing the bridge certificate.");
            Directory.CreateDirectory(directory); var path = Path.Combine(directory, "server-certificate.dpapi");
            if (File.Exists(path))
            {
                var pfx = Protect(File.ReadAllBytes(path), decrypt: true);
                try
                {
                    var saved = ImportForTls(pfx);
                    if (!saved.HasPrivateKey) { saved.Dispose(); throw new CryptographicException("Stored certificate has no private key."); }
                    if (saved.NotAfter.ToUniversalTime() > DateTime.UtcNow) return saved;
                    saved.Dispose();
                }
                finally { CryptographicOperations.ZeroMemory(pfx); }
            }
            using var key = RSA.Create(3072);
            var request = new CertificateRequest("CN=Win2Mobile Local Bridge", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
            request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
            request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new("1.3.6.1.5.5.7.3.1") }, true));
            var san = new SubjectAlternativeNameBuilder(); san.AddDnsName("localhost"); san.AddIpAddress(System.Net.IPAddress.Loopback); request.CertificateExtensions.Add(san.Build());
            using var generated = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddYears(5));
            var bytes = generated.Export(X509ContentType.Pfx);
            try
            {
                var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
                try { File.WriteAllBytes(temporary, Protect(bytes, decrypt: false)); File.Move(temporary, path, true); }
                finally { if (File.Exists(temporary)) File.Delete(temporary); }
                return ImportForTls(bytes);
            }
            finally { CryptographicOperations.ZeroMemory(bytes); }
        }
        finally { if (acquired) mutex.ReleaseMutex(); }
    }
    // Do not use PersistKeySet: only the encrypted DPAPI file should outlive this object.
    private static X509Certificate2 ImportForTls(byte[] pfx)
        => X509CertificateLoader.LoadPkcs12(pfx, null, X509KeyStorageFlags.UserKeySet);
    [StructLayout(LayoutKind.Sequential)] private struct Blob { public int Length; public IntPtr Data; }
    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)] private static extern bool CryptProtectData(ref Blob input, string? description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("crypt32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)] private static extern bool CryptUnprotectData(ref Blob input, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr memory);
    private static byte[] Protect(byte[] bytes, bool decrypt)
    {
        var input = new Blob { Length = bytes.Length, Data = Marshal.AllocHGlobal(bytes.Length) };
        Blob output = default;
        try
        {
            Marshal.Copy(bytes, 0, input.Data, bytes.Length);
            bool success = decrypt ? CryptUnprotectData(ref input, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output) : CryptProtectData(ref input, null, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output);
            if (!success) throw new CryptographicException(Marshal.GetLastWin32Error());
            var result = new byte[output.Length]; Marshal.Copy(output.Data, result, 0, output.Length); return result;
        }
        finally
        {
            // The unmanaged buffers also briefly hold private key material.
            for (int i = 0; i < input.Length; i++) Marshal.WriteByte(input.Data, i, 0);
            Marshal.FreeHGlobal(input.Data);
            if (output.Data != IntPtr.Zero) { for (int i = 0; i < output.Length; i++) Marshal.WriteByte(output.Data, i, 0); LocalFree(output.Data); }
        }
    }
}
