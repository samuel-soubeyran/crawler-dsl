#!/usr/bin/env groovy

import org.codehaus.groovy.control.CompilerConfiguration

def cli = new CliBuilder()
def options = cli.parse(args)

if(options.arguments().size()<2){
    println "You must pass the name of a crawlerfile and a start input"
    println "e.g."
    println "./crawler.groovy tripadvisor.crawlerfile http://www.tripadvisor.com/AllLocations-g1-Places-World.html"
}

def crawlerfilename = options.arguments().first()
def crawlerStartInput = options.arguments().get(1)


class Emitted {

}

class Emitter {
    Closure sendTo
    Closure action = { return it }
    Closure builder = { return it }
    Emitter nextEmitter

    public Object getValue(Object input){
        Object output = builder(action(input))

        if(nextEmitter != null){
            output = nextEmitter.getValue(output)
        }
        return output
    }

    public Emitter getLastEmitter(){
        if(this.nextEmitter == null){
            return this
        }
        return this.nextEmitter.getLastEmitter()
    }
}

abstract class Step {
    String name
    Emitter currentEmitter = null
    List<Emitter> outputs = new ArrayList<>()

    public Map emit(Closure emitted){
        [
                to: { closure ->
                    if(currentEmitter == null){
                        currentEmitter = new Emitter()
                    }
                    currentEmitter.sendTo = closure
                    currentEmitter.builder = emitted
                    outputs.add(currentEmitter)
                    currentEmitter = null
                }
        ]

    }
    public void recipe(Closure c){
        c.delegate = this
        c.call()
    }

    public void apply (closure){
        Emitter e = new Emitter()
        e.action = closure
        if(currentEmitter != null){
            currentEmitter.nextEmitter = e
        }
        currentEmitter = e
    }


    public abstract Object exec(String s)
}

class Download extends Step {

    public Object exec(String input){
        println "downloading url " + input
        "aaaaaaabbbbbbbbccccccccdddddddddeeeeeeefffffffffff"
    }
}

class Parse extends Step {
    public Object exec(String input){
        println "parsing content " + input
        input
    }
}

abstract class Crawlerfile extends Script {
    LinkedHashMap<String, Object> download = new LinkedHashMap<>()
    LinkedHashMap<String, Object> parse = new LinkedHashMap<>()

    def getStartStepClosure = {}
    def link = { return it }
    def object = { dict -> {
            obj ->
                def res = [:]
                dict.each {
                    k, v -> res[k] = v(obj)
                }
                return res
        }
    }
    def page = { return it }

    public Step getStartStep(){
        return getStartStepClosure.call()
    }

    public Download download(String name){
        def step = download.get(name);
        if(step == null ){
            step = new Download()
            step.name = name
            download.put(name, step)
        }
        return step
    }

    public Parse parse(String name) {
        def step = parse.get(name);
        if(step == null ){
            step = new Parse()
            step.name = name
            parse.put(name, step)
        }
        return step
    }

    public Closure start(Closure getStartStep) {
        getStartStepClosure = getStartStep
    }

}

def crawlerConfig = new CompilerConfiguration();
crawlerConfig.scriptBaseClass = Crawlerfile.class.getName();

def shell = new GroovyShell(this.class.classLoader, crawlerConfig)

def crawlerfileContents = new File(crawlerfilename).getText('UTF-8')

def crawlerfile = shell.evaluate(crawlerfileContents + "\nreturn this\n")

public class StepExecution{
    Step step
    Object input
}

public class CrawlerfileExecutor {

    public void run(Crawlerfile crawlerfile, Object crawlerStartInput) {
        StepExecution exec = new StepExecution()
        exec.input = crawlerStartInput
        exec.step = crawlerfile.getStartStep()
        List<StepExecution> steps = new ArrayList<>()
        steps.add(exec)
        while(steps.size() > 0){
            for(StepExecution stepExecution : steps){
                steps.remove(stepExecution);
                steps.addAll(runStep(stepExecution))
            }
        }
    }
    public static List<StepExecution> runStep(StepExecution current){
        String output = current.step.exec(current.input)
        def res = new ArrayList()
        List<Emitter> emitters = current.step.getOutputs()
        for(Emitter e : emitters){
            StepExecution stepExecution = new StepExecution()
            stepExecution.step = e.getLastEmitter().sendTo();
            stepExecution.input = e.getValue(output)
            res.add(stepExecution)
        }
        return res
    }
}

new CrawlerfileExecutor().run(crawlerfile, crawlerStartInput)