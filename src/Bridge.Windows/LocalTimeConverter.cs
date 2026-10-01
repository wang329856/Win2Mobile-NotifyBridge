using System;
using System.Globalization;
using System.Windows.Data;

namespace Win2Mobile.Windows;

public sealed class LocalTimeConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is DateTimeOffset time ? time.ToLocalTime().ToString("MM-dd HH:mm:ss", culture) : "";
    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => throw new NotSupportedException();
}
