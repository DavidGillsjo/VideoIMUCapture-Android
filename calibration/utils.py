import yaml
# Subclass dumper to get indentation in YAML, required for OpenCV
class OpenCVDumper(yaml.SafeDumper):
    def __init__(self, stream, **kwargs):
        kwargs['default_flow_style'] = None
        kwargs['explicit_start'] = True
        #Write header
        stream.write('%YAML:1.0\n')
        super(OpenCVDumper, self).__init__(stream, **kwargs)

    def increase_indent(self, flow=False, indentless=False):
        return super(OpenCVDumper, self).increase_indent(flow, False)
