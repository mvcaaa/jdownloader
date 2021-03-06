package jd.gui.swing.jdgui.views.settings.panels.advanced;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.appwork.scheduler.DelayedRunnable;
import org.appwork.utils.swing.HelpNotifier;
import org.appwork.utils.swing.HelpNotifierCallbackListener;
import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.settings.AbstractConfigPanel;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.images.AbstractIcon;
import org.jdownloader.images.NewTheme;
import org.jdownloader.settings.advanced.AdvancedConfigEventListener;
import org.jdownloader.settings.advanced.AdvancedConfigManager;
import org.jdownloader.translate._JDT;

import jd.gui.swing.jdgui.JDGui;

public class AdvancedSettings extends AbstractConfigPanel implements DocumentListener, AdvancedConfigEventListener {

    /*
     * (non-Javadoc)
     *
     * @see org.jdownloader.gui.settings.AbstractConfigPanel#onShow()
     */
    @Override
    protected void onShow() {
        super.onShow();
        AdvancedConfigManager.getInstance().getEventSender().addListener(this);
        JDGui.help(_GUI.T.AdvancedSettings_onShow_title_(), _GUI.T.AdvancedSettings_onShow_msg_(), new AbstractIcon(IconKey.ICON_WARNING, 32));

    }

    /*
     * (non-Javadoc)
     *
     * @see org.jdownloader.gui.settings.AbstractConfigPanel#onHide()
     */
    @Override
    protected void onHide() {
        super.onHide();
        AdvancedConfigManager.getInstance().getEventSender().removeListener(this);
    }

    private static final long serialVersionUID = 1L;
    private JTextField        filterText;
    private String            filterHelp;
    private AdvancedTable     table;
    private DelayedRunnable   delayedRefresh;

    public String getTitle() {
        return _GUI.T.gui_settings_advanced_title();
    }

    public AdvancedSettings() {
        super();
        this.addHeader(getTitle(), NewTheme.I().getIcon(IconKey.ICON_ADVANCEDCONFIG, 32));
        this.addDescription(_JDT.T.gui_settings_advanced_description());

        filterText = new JTextField() {

            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);

                Graphics2D g2 = (Graphics2D) g;
                Composite comp = g2.getComposite();

                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
                new AbstractIcon(IconKey.ICON_SEARCH, 16).paintIcon(this, g2, 3, 3);

                g2.setComposite(comp);
            }

        };

        HelpNotifier.register(filterText, new HelpNotifierCallbackListener() {

            public void onHelpNotifyShown(JComponent c) {
            }

            public void onHelpNotifyHidden(JComponent c) {
            }
        }, filterHelp = _GUI.T.AdvancedSettings_AdvancedSettings_filter_());

        // filterText.setOpaque(false);
        // filterText.putClientProperty("Synthetica.opaque", Boolean.FALSE);
        // filterText.setBorder(null);
        filterText.setBorder(BorderFactory.createCompoundBorder(filterText.getBorder(), BorderFactory.createEmptyBorder(0, 20, 0, 0)));
        add(filterText, "gapleft " + getLeftGap() + ",spanx,growx,pushx");
        filterText.getDocument().addDocumentListener(this);
        add(new JScrollPane(table = new AdvancedTable()));
        delayedRefresh = new DelayedRunnable(200, 1000) {

            @Override
            public String getID() {
                return "AdvancedSettings";
            }

            @Override
            public void delayedrun() {
                if (!filterText.getText().equals(filterHelp)) {
                    table.filter(filterText.getText());
                } else {
                    table.filter(null);
                }
            }

        };
    }

    @Override
    public Icon getIcon() {
        return NewTheme.I().getIcon(IconKey.ICON_ADVANCEDCONFIG, 20);
    }

    @Override
    public void save() {

    }

    @Override
    public void updateContents() {
        delayedRefresh.resetAndStart();
    }

    public void insertUpdate(DocumentEvent e) {
        delayedRefresh.resetAndStart();
    }

    public void removeUpdate(DocumentEvent e) {
        delayedRefresh.resetAndStart();
    }

    public void changedUpdate(DocumentEvent e) {
        delayedRefresh.resetAndStart();
    }

    public void onAdvancedConfigUpdate() {
        delayedRefresh.resetAndStart();
    }
}