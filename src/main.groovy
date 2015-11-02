#!/usr/bin/env groovy
import org.codehaus.groovy.control.CompilerConfiguration
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

import java.util.regex.Matcher
import java.util.regex.Pattern

@Grapes( @Grab('org.jsoup:jsoup:1.6.1'))

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
    Closure sendTo
    public void to(Closure sendTo){
        this.sendTo = sendTo
    }
    public Object build(Object input){
        return input
    }
}

class ObjectEmitted extends Emitted {
    Map<Object, Closure> fields;

    public Emitted fields(Map) {
        this.fields = fields
        return this
    }

    public Object build(Object input){
        Map<Object, Object> res = new LinkedHashMap()
        this.fields.each { k, v ->
            res.put(k, v.call(input))
        }
        return res
    }
}

class Emitter {
    Emitted emitted
    Closure action = { return it }
    Emitter nextEmitter

    public Object getValue(Object input){
        Object output = action(input)
        if(emitted != null){
            output = emitted.build(output)
        }
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

    public Emitted emit(Closure emittedFactory){
        if(currentEmitter == null){
            currentEmitter = new Emitter()
        }
        currentEmitter.emitted = emittedFactory()
        outputs.add(currentEmitter)
        return currentEmitter.emitted
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


    public abstract Object exec(Object s)
}

class Download extends Step {

    public Object exec(Object input){
        println "downloading url " + input
        "aaaaaaabbbbbbbbccccccccdddddddddeeeeeeefffffffffff"
    }
}

class Parse extends Step {
    public Object exec(Object input){
        println "parsing content " + input
        input
    }
}

abstract class Crawlerfile extends Script {
    LinkedHashMap<String, Object> download = new LinkedHashMap<>()
    LinkedHashMap<String, Object> parse = new LinkedHashMap<>()

    def getStartStepClosure = {}
    def link = { return new Emitted() }
    def page = { return new Emitted() }
    def object = { return new ObjectEmitted() }

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

    public Closure attributeSelector(String selector){
        return {
            what ->
                List<Object> obj = new ArrayList<>()
                what.results.each { r ->
                    if(r != null) {
                        Document doc = Jsoup.parse(r)
                        if (doc.body().children() != 0) {
                            Element e = doc.body().child(0)
                            if (e != null) {
                                obj.add(e.attr(selector))
                            }
                        }
                    }
                }
                what.results = obj;
        }
    }

    public Closure regexp(String regexp){
        return {
            what ->
                List<Object> obj = new ArrayList<>()
                what.results.each { r ->
                    Pattern p = Pattern.compile(regexp);
                    Matcher m = p.matcher(r);
                    while(m.find()){
                        obj.add(m.group())
                    }
                }
                what.results = obj;
        }
    }

    public Closure extractCrawledUrl(){
        return {
            what ->
                List<Object> obj = new ArrayList<>()
                what.results.each {
                    obj.add(what.request.url)
                }
                what.results = obj
        }
    }

    public Closure toFirstElement(){
        return {
            what ->
                what.results = what.results.get(0)
        }
    }

    public Closure textCssSelector(String selector){
        
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
            stepExecution.step = e.getLastEmitter().getEmitted().sendTo();
            stepExecution.input = e.getValue(output)
            res.add(stepExecution)
        }
        return res
    }
}

new CrawlerfileExecutor().run(crawlerfile, crawlerStartInput)