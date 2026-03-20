from PySide6.QtCore import QObject, QRunnable, Signal, Slot
import traceback
import sys

class WorkerSignals(QObject):
    """
    Defines the signals available from a running worker thread.
    Supported signals are:
    finished
        No data
    error
        tuple (exctype, value, traceback.str)
    result
        object data returned from processing, anything
    """
    finished = Signal()
    error = Signal(tuple)
    result = Signal(object)
    progress = Signal(object)

class Worker(QRunnable):
    """
    Worker thread
    Inherits from QRunnable to handle worker thread setup, signals and wrap-up.
    :param callback: The function callback to run on this worker thread. Supplied args and
                     kwargs will be passed through to the runner.
    :type callback: function
    :param args: Arguments to pass to the callback function
    :param kwargs: Keywords to pass to the callback function
    """
    def __init__(self, fn, *args, **kwargs):
        super(Worker, self).__init__()
        
        # Check if we need to pass the progress signal to the callback
        self.pass_progress = kwargs.pop('pass_progress', False)
        
        # Store constructor arguments (re-used for processing)
        self.fn = fn
        self.args = args
        self.kwargs = kwargs
        self.signals = WorkerSignals()

    @Slot()
    def run(self):
        """
        Initialise the runner function with passed args, kwargs.
        """
        try:
            if self.pass_progress:
                # Add progress signal to kwargs
                self.kwargs['progress_callback'] = self.signals.progress
            
            result = self.fn(*self.args, **self.kwargs)
        except:
            traceback.print_exc()
            exctype, value = sys.exc_info()[:2]
            self.signals.error.emit((exctype, value, traceback.format_exc()))
        else:
            self.signals.result.emit(result)
        finally:
            self.signals.finished.emit()
