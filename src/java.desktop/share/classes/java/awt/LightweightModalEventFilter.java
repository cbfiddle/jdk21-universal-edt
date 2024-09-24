package java.awt;

/**
 * An event filter associated with lightweight modal dialogs.
 */
public abstract class LightweightModalEventFilter implements EventFilter {

    /** The lighweight modal dialog */
    protected Component modalComponent;

    /**
     * Initialize this base class.
     * @param modalDialog The modal dialog.
     */
    protected LightweightModalEventFilter(Component modalDialog) {
        this.modalComponent = modalDialog;
    }

    /**
     * Return the lightweight modal dialog.
     * @return the lightweight modal dialog.
     */
    public Component getModalDialog() {
        return modalComponent;
    }
}
