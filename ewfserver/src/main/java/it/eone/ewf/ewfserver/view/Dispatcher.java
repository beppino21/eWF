package it.eone.ewf.ewfserver.view;

import org.eclnt.workplace.IWorkpageContainer;
import org.eclnt.workplace.WorkpageDispatcher;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.ApplicationErrorInfo;
import org.eclnt.jsfserver.elements.ApplicationErrorInfoDuringSet;
import org.eclnt.jsfserver.util.IErrorAware;
import org.eclnt.util.valuemgmt.ValueManager;

/*
 * The dispatcher is referenced in faces-config.xml. When changing the package
 * of the dispatcher, then also update the faces-config.xml link!<br>
 * <br>
 * The dispatcher is the fist name that is referenced by application expressions.
 * It internally is containing a name resolution by providing a Map-implementation,
 * so that e.g. #{d.XxxYyy} references an instance of class "XxxYyy" - following
 * diverse naming patterns.
 */
public class Dispatcher extends WorkpageDispatcher
    implements IErrorAware
{
    // ------------------------------------------------------------------------
    // constructors
    // ------------------------------------------------------------------------

    /** 
     * Constructor that is used for the root dispatcher, e.g. "#{d}". 
     */
    public Dispatcher()
    {
        // add any implementation...
    }

    /** 
     * Dispatcher that is used for the sub dispatcher objects, e.g. "#{d.d_1}". 
     */
    public Dispatcher(IWorkpageContainer workpageContainer)
    {
        super(workpageContainer);
        // add any implementation...
    }
    
    // ------------------------------------------------------------------------
    // public usage
    // ------------------------------------------------------------------------

    /**
     * This method needs to be implemented if you want to extend the information
     * within the page bean browser tool. Otherwise leave unchanged.
     */
    public static DispatcherInfo getStaticDispatcherInfo() { return new DispatcherInfo(Dispatcher.class); }
    
    /** 
     * Convenience access to access the root dispatcher in the current dialog session. 
     */
    public static Dispatcher getDialogSessionInstance() 
    {
        // correctly casting the generic dispatcher into this class
        return (Dispatcher)WorkpageDispatcher.getDialogSessionInstance();
    }
    
    // ------------------------------------------------------------------------
    // IErrorAware - "last level" of managing errors during the processing
    //               of a request
    // ------------------------------------------------------------------------

    @Override
    public void onApplicationError(ApplicationErrorInfo aei)
    {
        Statusbar.outputError(aei.getException().toString(),ValueManager.getStackstraceString(aei.getException()));
    }

    @Override
    public void onApplicationErrorDuringSet(ApplicationErrorInfoDuringSet aeids)
    {
        throw new Error(aeids.getException());
    }
}
