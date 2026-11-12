package local.autoclaw.mypycharm;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** 设置页（对齐 myVscode.autoDiscover / myVscode.bindings 配置项）。 */
public final class MyConfigurable implements Configurable {
    private final Project project;
    private JCheckBox autoDiscover;
    private JCheckBox openMdInSplitDown;
    private JCheckBox syncFileCopyContent;
    private DefaultTableModel model;
    private JTable table;
    private boolean modified;

    public MyConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public String getDisplayName() {
        return "My PyCharm";
    }

    @Override
    public @Nullable String getHelpTopic() {
        return null;
    }

    @Override
    public @Nullable JComponent createComponent() {
        MySettings st = MySettings.getInstance(project);
        autoDiscover = new JCheckBox("自动解析各根目录下 *-gl.sh 包装脚本中的 --git-dir / --work-tree", st.getState().autoDiscover);
        autoDiscover.addActionListener(l -> modified = true);
        openMdInSplitDown = new JCheckBox("新建同名 MD 文件后在下方分栏打开", st.getState().openMdInSplitDown);
        openMdInSplitDown.addActionListener(l -> modified = true);
        syncFileCopyContent = new JCheckBox("生成同步文件时复制原文件内容（关闭则创建空文件）", st.getState().syncFileCopyContent);
        syncFileCopyContent.addActionListener(l -> modified = true);

        model = new DefaultTableModel(new Object[]{"工作树", "git 目录", "名称", "强制接管"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 3 ? Boolean.class : String.class;
            }
        };
        table = new JTable(model);
        table.setRowHeight(26);
        for (MySettings.BindingDef d : st.getState().bindings) {
            model.addRow(new Object[]{d.workTree, d.gitDir, d.label, d.force});
        }
        model.addTableModelListener(l -> modified = true);

        JButton add = new JButton("添加");
        add.addActionListener(l -> model.addRow(new Object[]{"", "", "", Boolean.FALSE}));
        JButton remove = new JButton("删除选中");
        remove.addActionListener(l -> {
            int[] rows = table.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--) {
                model.removeRow(table.convertRowIndexToModel(rows[i]));
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(add);
        buttons.add(remove);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(JBUI.Borders.empty(8));
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        autoDiscover.setAlignmentX(Component.LEFT_ALIGNMENT);
        openMdInSplitDown.setAlignmentX(Component.LEFT_ALIGNMENT);
        syncFileCopyContent.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(autoDiscover);
        north.add(openMdInSplitDown);
        north.add(syncFileCopyContent);
        panel.add(north, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    @Override
    public boolean isModified() {
        return modified;
    }

    @Override
    public void apply() {
        MySettings st = MySettings.getInstance(project);
        st.getState().autoDiscover = autoDiscover.isSelected();
        st.getState().openMdInSplitDown = openMdInSplitDown.isSelected();
        st.getState().syncFileCopyContent = syncFileCopyContent.isSelected();
        List<MySettings.BindingDef> defs = new ArrayList<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            String wt = str(model.getValueAt(i, 0));
            String gd = str(model.getValueAt(i, 1));
            if (wt.isBlank() || gd.isBlank()) {
                continue;
            }
            Object f = model.getValueAt(i, 3);
            defs.add(new MySettings.BindingDef(wt.trim(), gd.trim(), str(model.getValueAt(i, 2)),
                    Boolean.TRUE.equals(f)));
        }
        st.getState().bindings = defs;
        modified = false;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            MyReposService svc = MyReposService.getInstance(project);
            svc.reload();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    svc.applyDirectoryMappings();
                }
            });
        });
    }

    @Override
    public void reset() {
        MySettings st = MySettings.getInstance(project);
        autoDiscover.setSelected(st.getState().autoDiscover);
        openMdInSplitDown.setSelected(st.getState().openMdInSplitDown);
        syncFileCopyContent.setSelected(st.getState().syncFileCopyContent);
        model.setRowCount(0);
        for (MySettings.BindingDef d : st.getState().bindings) {
            model.addRow(new Object[]{d.workTree, d.gitDir, d.label, d.force});
        }
        modified = false;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
