using NotificationService.Services;
using System.Text.Json;

namespace NotificationService;

public class BlacklistForm : Form
{
    private readonly AppSettings _settings;
    private ListBox _listBox = null!;
    private TextBox _addBox = null!;

    public BlacklistForm(AppSettings settings)
    {
        _settings = settings;
        Text = "Blacklist Management";
        Size = new Size(350, 350);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;

        var panel = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(12) };
        panel.RowStyles.Add(new RowStyle(SizeType.Absolute, 24));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        panel.RowStyles.Add(new RowStyle(SizeType.Absolute, 36));

        panel.Controls.Add(new Label { Text = "Blocked Applications:", Font = new Font("Segoe UI", 10, FontStyle.Bold) }, 0, 0);

        _listBox = new ListBox { Dock = DockStyle.Fill };
        foreach (var app in _settings.BlockedApps)
            _listBox.Items.Add(app);
        panel.Controls.Add(_listBox, 0, 1);

        var bottomPanel = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight };
        _addBox = new TextBox { Width = 150, PlaceholderText = "App name to block" };
        var addBtn = new Button { Text = "Add", Width = 60 };
        var removeBtn = new Button { Text = "Remove Selected", Width = 110 };
        var saveBtn = new Button { Text = "Save & Close", Width = 100 };

        addBtn.Click += (_, _) =>
        {
            var name = _addBox.Text.Trim();
            if (!string.IsNullOrWhiteSpace(name) && !_listBox.Items.Contains(name))
            {
                _listBox.Items.Add(name);
                _addBox.Clear();
            }
        };

        removeBtn.Click += (_, _) =>
        {
            while (_listBox.SelectedItems.Count > 0)
                _listBox.Items.Remove(_listBox.SelectedItems[0]);
        };

        saveBtn.Click += (_, _) =>
        {
            _settings.BlockedApps.Clear();
            foreach (var item in _listBox.Items)
                _settings.BlockedApps.Add(item.ToString()!);

            try
            {
                var config = new { AppSettings = _settings };
                var json = JsonSerializer.Serialize(config, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText("appsettings.json", json);
            }
            catch { }

            DialogResult = DialogResult.OK;
            Close();
        };

        bottomPanel.Controls.Add(_addBox);
        bottomPanel.Controls.Add(addBtn);
        bottomPanel.Controls.Add(removeBtn);
        bottomPanel.Controls.Add(saveBtn);
        panel.Controls.Add(bottomPanel, 0, 2);

        Controls.Add(panel);
    }
}
